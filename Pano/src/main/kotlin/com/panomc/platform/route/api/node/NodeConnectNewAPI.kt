package com.panomc.platform.route.api.node

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.panel.permission.ManageNodesPermission
import com.panomc.platform.config.ConfigManager
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.Node
import com.panomc.platform.error.InstallationRequired
import com.panomc.platform.error.InvalidNodePairingCode
import com.panomc.platform.error.InvalidPublicKey
import com.panomc.platform.model.*
import com.panomc.platform.node.AgentNodeDirectory
import com.panomc.platform.node.NodeBootstrap
import com.panomc.platform.node.NodeBootstrapTokenStore
import com.panomc.platform.node.NodeKind
import com.panomc.platform.node.NodePairingCodeManager
import com.panomc.platform.node.NodeProtocol
import com.panomc.platform.node.NodeRuntime
import com.panomc.platform.node.NodeStatus
import com.panomc.platform.notification.NotificationManager
import com.panomc.platform.notification.type.panel.NodeConnectRequestNotification
import com.panomc.platform.panel.PanelRealtimeHub
import com.panomc.platform.setup.SetupManager
import com.panomc.platform.token.NodeAuthenticationTokenType
import com.panomc.platform.token.TokenProvider
import com.panomc.platform.util.Aes256GcmUtil
import com.panomc.platform.util.EncryptUtil
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.RequestPredicate
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Bodies.json
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.*
import java.security.KeyFactory
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.UUID

/**
 * Pairs a node daemon with this Pano (`POST /api/node/connect`).
 *
 * Mirrors the Minecraft server handshake: the caller sends an RSA public key, Pano generates the
 * AES-256 key both sides will frame messages with, wraps it with that public key and hands back a
 * long-lived JWT. Storing the key in plaintext on this side is what lets Pano decrypt the socket;
 * it never leaves the platform (see [Node.toPublicJsonObject]).
 *
 * Two ways in, and the difference decides whether a human has to say yes. A `bootstrapToken` is
 * one Pano itself minted moments earlier when it spawned the local node, so that node is approved
 * on the spot. A `pairingCode` is the rotating six-digit code an admin read out of the panel, and
 * a node arriving that way waits for someone to accept it.
 *
 * A third: an *agent* code from `GET /api/panel/servers/agent-link` pairs a Pano Agent, a daemon
 * dedicated to one existing server. It is approved on the spot too (only someone allowed to create
 * servers can see that code), no "new node" notification goes out, and the row is marked `agent`,
 * which keeps it off every node list: its first hello adopts the server and the panel shows only
 * that. Whether a daemon is an agent is decided by the code it used, never by the `agent` flag it
 * sends -- a daemon that says so with a node code is an ordinary node waiting for approval. Agent
 * codes are single use (SM-74): the one used here is gone, and the user it was minted for is
 * credited with the server the agent creates. With `managed-servers.accept-agent-links` off (SM-77)
 * an agent code is refused exactly like a wrong code; node pairing codes and bootstrap tokens are
 * not affected.
 */
@Endpoint
class NodeConnectNewAPI(
    private val nodePairingCodeManager: NodePairingCodeManager,
    private val nodeBootstrapTokenStore: NodeBootstrapTokenStore,
    private val databaseManager: DatabaseManager,
    private val tokenProvider: TokenProvider,
    private val setupManager: SetupManager,
    private val notificationManager: NotificationManager,
    private val panelRealtimeHub: PanelRealtimeHub,
    private val authProvider: AuthProvider,
    private val agentNodeDirectory: AgentNodeDirectory,
    private val configManager: ConfigManager
) : Api() {
    override val paths = listOf(Path("/api/node/connect", RouteType.POST))

    // Daemon surface, authenticated with a pairing secret and never with a user session, so a
    // user-permission bypass could not apply here.
    override val maintenanceAccess = MaintenanceAccess.ALWAYS

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .body(
                json(
                    objectSchema()
                        .optionalProperty("pairingCode", stringSchema())
                        .optionalProperty("bootstrapToken", stringSchema())
                        .requiredProperty("name", stringSchema())
                        .requiredProperty("publicKey", stringSchema())
                        .optionalProperty("version", stringSchema())
                        .optionalProperty("protocolVersion", intSchema())
                        .optionalProperty("os", stringSchema())
                        .optionalProperty("arch", stringSchema())
                        .optionalProperty("hostname", stringSchema())
                        .optionalProperty("dataPath", stringSchema())
                        .optionalProperty("runtime", stringSchema())
                        // Sent by a Pano Agent (protocol 5); informational, see the class comment.
                        .optionalProperty("agent", booleanSchema())
                        .optionalProperty("agentServer", stringSchema())
                )
            )
            .predicate(RequestPredicate.BODY_REQUIRED)
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        if (!setupManager.isSetupDone()) {
            throw InstallationRequired()
        }

        val data = getParameters(context).body().jsonObject

        val bootstrapToken = data.getString("bootstrapToken")

        // The bootstrap token is consumed first and unconditionally: it is single use, so even a
        // request that goes on to fail must burn it rather than leave it replayable.
        val grant = if (bootstrapToken.isNullOrBlank()) null else nodeBootstrapTokenStore.consume(bootstrapToken)
        val usedBootstrapToken = grant != null

        // Taken, not just compared: an agent code pairs exactly one agent, and a second daemon
        // arriving with it a moment later is refused like any wrong code. While agent links are
        // switched off (`managed-servers.accept-agent-links`, SM-77) no agent code is anything, so
        // it falls through to the node code check below and gets that check's answer -- nothing
        // tells the caller the code once existed.
        val agentCode = if (usedBootstrapToken) null else nodePairingCodeManager.takeAgentCode(
            data.getString("pairingCode"),
            acceptAgentLinks = configManager.config.effectiveManagedServers.acceptAgentLinks
        )
        val usedAgentCode = agentCode != null

        if (!usedBootstrapToken && !usedAgentCode && !nodePairingCodeManager.matches(data.getString("pairingCode"))) {
            throw InvalidNodePairingCode()
        }

        val approved = usedBootstrapToken || usedAgentCode

        val encodedAesKey = Aes256GcmUtil.generateBase64Key256()

        val encryptedAesKey = try {
            val decodedPublicKey = Base64.getDecoder().decode(data.getString("publicKey"))

            val keySpec = X509EncodedKeySpec(decodedPublicKey)
            val keyFactory = KeyFactory.getInstance("RSA")
            val restoredPublicKey = keyFactory.generatePublic(keySpec)

            String(Base64.getEncoder().encode(EncryptUtil.encryptData(encodedAesKey, restoredPublicKey)))
        } catch (_: Exception) {
            // Nothing was paired, so the admin's command must still work once the daemon is fixed.
            agentCode?.let { nodePairingCodeManager.returnAgentCode(it) }

            throw InvalidPublicKey()
        }

        val now = System.currentTimeMillis()

        val node = Node(
            uuid = UUID.randomUUID().toString(),
            name = (data.getString("name") ?: "").trim().take(MAX_NAME_LENGTH).ifEmpty { DEFAULT_NAME },
            // Both come from the token rather than from the request: the daemon says what it is
            // called and where it lives, never what it is allowed to be.
            kind = grant?.kind ?: NodeKind.REMOTE,
            bootstrap = grant?.bootstrap ?: NodeBootstrap.MANUAL,
            runtime = NodeRuntime.fromId(data.getString("runtime")),
            status = NodeStatus.OFFLINE,
            approved = approved,
            version = data.getString("version")?.take(MAX_SHORT_FIELD),
            protocolVersion = data.getInteger("protocolVersion") ?: NodeProtocol.LEGACY_PROTOCOL_VERSION,
            os = data.getString("os")?.take(MAX_SHORT_FIELD),
            arch = data.getString("arch")?.take(MAX_SHORT_FIELD),
            hostname = data.getString("hostname")?.take(MAX_NAME_LENGTH),
            remoteAddress = authProvider.getRemoteIP(context),
            dataPath = data.getString("dataPath")?.take(MAX_PATH_FIELD),
            aesKey = encodedAesKey,
            addedTime = now,
            acceptedTime = if (approved) now else 0,
            agent = usedAgentCode
        )

        val sqlClient = getSqlClient()

        val nodeId = databaseManager.nodeDao.add(node, sqlClient)

        val (token, expireDate) = tokenProvider.generateToken(nodeId.toString(), NodeAuthenticationTokenType)

        tokenProvider.saveToken(token, nodeId.toString(), NodeAuthenticationTokenType, expireDate, sqlClient)

        if (agentCode != null) {
            databaseManager.nodeDao.getById(nodeId, sqlClient)?.let { agentNodeDirectory.put(it) }

            agentNodeDirectory.noteLinker(nodeId, agentCode.userId)
        }

        if (!approved) {
            notificationManager.sendNotificationToAllWithPermission(
                NodeConnectRequestNotification(nodeId, node.name),
                ManageNodesPermission(),
                sqlClient
            )
        }

        panelRealtimeHub.notifyNodeUpdated(nodeId)

        return Successful(
            mapOf(
                "token" to token,
                "encryptionKey" to encryptedAesKey
            )
        )
    }

    companion object {
        private const val MAX_NAME_LENGTH = 255
        private const val MAX_SHORT_FIELD = 64
        private const val MAX_PATH_FIELD = 512
        private const val DEFAULT_NAME = "node"
    }
}
