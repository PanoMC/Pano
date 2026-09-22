package com.panomc.platform.route.api.panel.node

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.panel.permission.ManageNodesPermission
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.error.BadRequest
import com.panomc.platform.error.CurrentPasswordNotCorrect
import com.panomc.platform.model.*
import com.panomc.platform.node.PanoUrlOverride
import com.panomc.platform.node.ssh.NodeSshBootstrapService
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.RequestPredicate
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Bodies.json
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.*

/**
 * Starts installing the node daemon on a host over SSH (`POST /api/panel/nodes/ssh-bootstrap`).
 *
 * Answers with the host key fingerprint and installs nothing yet. The credential is held in
 * memory against the returned task and only used once somebody has confirmed the fingerprint,
 * because a first connection to an unknown host is where a man in the middle would be.
 *
 * Asks for the caller's own password like the other actions that reach outside Pano: a stolen
 * session must not be able to hand Pano's credentials to a machine of the thief's choosing.
 *
 * The optional `panoUrl` is the address *the node* should use to reach Pano, for NAT, tunnel and
 * lab setups where the public website URL does not resolve or does not route from the machine
 * being bootstrapped — an SSH reverse tunnel to a development Pano is the usual one. It replaces
 * the website URL in the installer's `--pano` argument and in the URL the daemon jar is
 * downloaded from; see [PanoUrlOverride] for what is accepted.
 */
@Endpoint
class PanelSshBootstrapNodeAPI(
    private val authProvider: AuthProvider,
    private val databaseManager: DatabaseManager,
    private val nodeSshBootstrapService: NodeSshBootstrapService
) : PanelApi() {
    override val paths = listOf(Path("/api/panel/nodes/ssh-bootstrap", RouteType.POST))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .body(
                json(
                    objectSchema()
                        .requiredProperty("currentPassword", stringSchema())
                        .requiredProperty("host", stringSchema())
                        .requiredProperty("username", stringSchema())
                        .requiredProperty(
                            "auth",
                            objectSchema()
                                .requiredProperty("type", stringSchema())
                                .optionalProperty("password", stringSchema())
                                .optionalProperty("privateKey", stringSchema())
                                .optionalProperty("passphrase", stringSchema())
                        )
                        .optionalProperty("port", intSchema())
                        .optionalProperty("sudo", booleanSchema())
                        .optionalProperty("name", stringSchema())
                        .optionalProperty("panoUrl", stringSchema())
                )
            )
            .predicate(RequestPredicate.BODY_REQUIRED)
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        authProvider.requirePermission(ManageNodesPermission(), context)

        val data = getParameters(context).body().jsonObject

        val sqlClient = getSqlClient()
        val userId = authProvider.getUserIdFromRoutingContext(context)

        if (!databaseManager.userDao.isPasswordCorrectWithId(userId, data.getString("currentPassword"), sqlClient)) {
            throw CurrentPasswordNotCorrect()
        }

        val host = data.getString("host").trim()
        val username = data.getString("username").trim()
        val port = data.getInteger("port") ?: DEFAULT_SSH_PORT

        if (host.isEmpty() || host.length > MAX_HOST_LENGTH || username.isEmpty() || port !in 1..65535) {
            throw BadRequest()
        }

        val panoUrl = PanoUrlOverride.sanitize(data.getString("panoUrl"))

        val auth = data.getJsonObject("auth")

        val credentials = when (auth.getString("type")?.lowercase()) {
            "password" -> NodeSshBootstrapService.Credentials(
                password = auth.getString("password")?.toCharArray() ?: throw BadRequest()
            )

            "key" -> NodeSshBootstrapService.Credentials(
                privateKey = auth.getString("privateKey")?.toCharArray() ?: throw BadRequest(),
                passphrase = auth.getString("passphrase")?.takeIf { it.isNotEmpty() }?.toCharArray()
            )

            else -> throw BadRequest()
        }

        val pending = try {
            nodeSshBootstrapService.start(
                target = NodeSshBootstrapService.Target(
                    host = host,
                    port = port,
                    username = username.take(MAX_NAME_LENGTH),
                    sudo = data.getBoolean("sudo", false) ?: false,
                    name = data.getString("name")?.trim()?.take(MAX_NAME_LENGTH)?.ifEmpty { null },
                    panoUrl = panoUrl
                ),
                credentials = credentials,
                createdBy = userId
            )
        } catch (e: Exception) {
            // The task row already carries the reason; the caller gets a plain refusal rather
            // than an SSH library's exception text.
            throw BadRequest()
        }

        return Successful(
            mapOf(
                "taskId" to pending.taskId,
                "taskUuid" to pending.taskUuid,
                "fingerprint" to pending.fingerprint
            )
        )
    }

    companion object {
        private const val DEFAULT_SSH_PORT = 22
        private const val MAX_HOST_LENGTH = 255
        private const val MAX_NAME_LENGTH = 64
    }
}
