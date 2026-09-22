package com.panomc.platform.route.api.panel.server.players

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.PlayerBanService
import com.panomc.platform.auth.panel.log.ServerPlayerActionLog
import com.panomc.platform.auth.panel.permission.ManagePlayersPermission
import com.panomc.platform.auth.panel.permission.ManageServerPlayersPermission
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.Server
import com.panomc.platform.error.BadRequest
import com.panomc.platform.error.NodeOffline
import com.panomc.platform.error.NotExists
import com.panomc.platform.error.ServerCapabilityMissing
import com.panomc.platform.error.ServerOffline
import com.panomc.platform.model.*
import com.panomc.platform.node.NodeManager
import com.panomc.platform.node.message.SendCommandMessage
import com.panomc.platform.server.ServerCapability
import com.panomc.platform.server.ServerManager
import com.panomc.platform.server.ServerPlayerAction
import com.panomc.platform.server.feature.ServerFeature
import com.panomc.platform.server.feature.ServerFeatureResolver
import com.panomc.platform.server.feature.ServerFeatureSource
import com.panomc.platform.server.message.ExecuteCommandMessage
import com.panomc.platform.server.message.PlayerActionMessage
import com.panomc.platform.server.players.ServerPlayerCommandComposer
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.RequestPredicate
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Bodies.json
import io.vertx.ext.web.validation.builder.Parameters.param
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.booleanSchema
import io.vertx.json.schema.common.dsl.Schemas.numberSchema
import io.vertx.json.schema.common.dsl.Schemas.objectSchema
import io.vertx.json.schema.common.dsl.Schemas.stringSchema
import io.vertx.sqlclient.SqlClient
import java.util.UUID

/**
 * Acts on one online player, through whichever source can reach them (SM-52, §2.4.17).
 *
 * The plugin path is unchanged: kick and message go to its own API on every platform, and the rest
 * become a console command it dispatches. The node path composes *the same* command strings and
 * writes them to stdin, so a server with no Pano plugin in it keeps its buttons instead of showing
 * a roster nobody can act on.
 *
 * Either way the command is built here from the username Pano has on record and never from
 * anything in the request body, so a crafted name cannot turn one command into two. The `:uuid`
 * segment is a player *identifier*: a real uuid for a plugin roster, and a username for the node's
 * ping sample, which has no uuids to give.
 */
@Endpoint
class PanelServerPlayerActionAPI(
    private val databaseManager: DatabaseManager,
    private val authProvider: AuthProvider,
    private val serverManager: ServerManager,
    private val nodeManager: NodeManager,
    private val serverFeatureResolver: ServerFeatureResolver,
    private val playerBanService: PlayerBanService
) : PanelApi() {
    /** The player an action names, however Pano happens to know about them. */
    private data class Target(val uuid: String?, val username: String)

    override val paths = listOf(Path("/api/panel/servers/:id/players/:uuid/action", RouteType.POST))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .pathParameter(param("id", numberSchema()))
            .pathParameter(param("uuid", stringSchema()))
            .body(
                json(
                    objectSchema()
                        .requiredProperty("action", stringSchema())
                        .optionalProperty("text", stringSchema())
                        .optionalProperty("gamemode", stringSchema())
                        // BAN only: an epoch-millisecond end (none = permanent) and whether the
                        // account's owner is emailed, exactly as the players page sends them.
                        .optionalProperty("duration", numberSchema())
                        .optionalProperty("sendNotification", booleanSchema())
                )
            )
            .predicate(RequestPredicate.BODY_REQUIRED)
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        val parameters = getParameters(context)
        val id = parameters.pathParameter("id").long

        authProvider.requirePermission(ManageServerPlayersPermission(), context, id)

        val identifier = parameters.pathParameter("uuid").string
        val body = parameters.body().jsonObject

        val action = ServerPlayerAction.fromId(body.getString("action")) ?: throw BadRequest()
        val text = body.getString("text")?.trim()
        val gamemode = body.getString("gamemode")?.trim()

        val sqlClient = getSqlClient()

        val server = databaseManager.serverDao.getById(id, sqlClient) ?: throw NotExists()

        if (!server.permissionGranted) {
            throw NotExists()
        }

        val source = serverFeatureResolver.pick(server, ServerFeature.PLAYERS_ACTIONS)

        val target = resolveTarget(id, identifier, source, sqlClient) ?: throw NotExists()

        if (action == ServerPlayerAction.MESSAGE && (text.isNullOrEmpty() || text.length > MAX_TEXT_LENGTH)) {
            throw BadRequest()
        }

        if (action == ServerPlayerAction.BAN && (text?.length ?: 0) > PlayerBanService.MAX_REASON_LENGTH) {
            throw BadRequest()
        }

        if (action == ServerPlayerAction.KICK && (text?.length ?: 0) > MAX_TEXT_LENGTH) {
            throw BadRequest()
        }

        val userId = authProvider.getUserIdFromRoutingContext(context)
        val username = databaseManager.userDao.getUsernameFromUserId(userId, sqlClient) ?: throw NotExists()

        var scope: String? = null

        when {
            action == ServerPlayerAction.BAN -> scope = ban(context, server, source, target, text, body, userId, username, sqlClient)
            source == ServerFeatureSource.NODE -> sendThroughNode(server, action, target, text, gamemode, username)
            else -> sendThroughPlugin(server, action, target, text, gamemode, username)
        }

        databaseManager.panelActivityLogDao.add(
            ServerPlayerActionLog(userId, username, id, target.username, action.name),
            sqlClient
        )

        return Successful(scope?.let { mapOf("scope" to it) } ?: mapOf())
    }

    /**
     * Bans [target] as far as Pano can reach, returning how far that was: [SCOPE_PANO] or
     * [SCOPE_SERVER].
     *
     * A player with a Pano account (the same name) is banned there, which is what the players page
     * does too: the website signs them out and every server with ban integration kicks them and
     * refuses them at login. That takes the global players permission, not just this server's.
     *
     * This server enforces that ban itself only when its plugin is the source and ban integration
     * is on. Otherwise - a node-only server, or integration switched off - the player is also
     * removed here directly: a permanent ban becomes the server's own `ban`, a temporary one a
     * kick, since the server's ban would outlast Pano's.
     *
     * Without an account there is nothing for Pano to ban, so the server's own `ban` is all there
     * is, and a duration is refused rather than silently turned into forever.
     */
    private suspend fun ban(
        context: RoutingContext,
        server: Server,
        source: ServerFeatureSource?,
        target: Target,
        reason: String?,
        body: JsonObject,
        issuerId: Long,
        issuedBy: String,
        sqlClient: SqlClient
    ): String {
        val bannedUntil = body.getLong("duration")
        val hasAccount = databaseManager.userDao.getUserIdFromUsername(target.username, sqlClient) != null

        if (!hasAccount) {
            if (bannedUntil != null) {
                throw BadRequest()
            }

            sendCommand(server, source, ServerPlayerCommandComposer.composeBan(server.type, target.username, reason), issuedBy)

            return SCOPE_SERVER
        }

        authProvider.requirePermission(ManagePlayersPermission(), context)

        playerBanService.ban(
            username = target.username,
            issuerId = issuerId,
            issuerIsAdmin = context.get<Boolean>("isAdmin") ?: false,
            reason = reason?.takeIf { it.isNotEmpty() },
            bannedUntil = bannedUntil,
            sendNotification = body.getBoolean("sendNotification") ?: false,
            sqlClient = sqlClient
        )

        val enforcedHere = source == ServerFeatureSource.PLUGIN && server.settings.banIntegration

        if (!enforcedHere) {
            val command = if (bannedUntil == null) {
                ServerPlayerCommandComposer.composeBan(server.type, target.username, reason)
            } else {
                ServerPlayerCommandComposer.composeForConsole(ServerPlayerAction.KICK, server.type, target.username, reason)
            }

            // The account is banned already; failing to reach this one server must not undo that
            // or report the whole ban as failed.
            runCatching { sendCommand(server, source, command, issuedBy) }
        }

        return SCOPE_PANO
    }

    /** One console line, through whichever side [source] says can run it on [server]. */
    private fun sendCommand(server: Server, source: ServerFeatureSource?, command: String, issuedBy: String) {
        if (source == ServerFeatureSource.NODE) {
            val nodeId = server.nodeId ?: throw ServerCapabilityMissing()
            val uuid = server.uuid ?: throw ServerCapabilityMissing()

            if (!nodeManager.sendMessage(nodeId, SendCommandMessage(uuid, command, issuedBy))) {
                throw NodeOffline()
            }

            return
        }

        if (!serverManager.isConnected(server.id)) {
            throw ServerOffline()
        }

        if (!server.hasCapability(ServerCapability.COMMANDS)) {
            throw ServerCapabilityMissing()
        }

        val message = ExecuteCommandMessage(command = command, requestId = UUID.randomUUID().toString(), issuedBy = issuedBy)

        if (!serverManager.sendMessage(server.id, message)) {
            throw ServerOffline()
        }
    }

    /**
     * Finds the player the request names.
     *
     * The plugin path only ever acts on someone `server_player` knows about, which is what makes
     * the username trustworthy. The node path has no such table — nothing reports joins for a
     * server with no plugin in it — so the ping sample is the roster, and the identifier is
     * matched against the names in it rather than accepted as given.
     */
    private suspend fun resolveTarget(
        serverId: Long,
        identifier: String,
        source: ServerFeatureSource,
        sqlClient: SqlClient
    ): Target? {
        val known = databaseManager.serverPlayerDao.getAllByServerId(serverId, sqlClient)
            .firstOrNull {
                it.uuid.toString().equals(identifier, ignoreCase = true) ||
                    it.username.equals(identifier, ignoreCase = true)
            }

        if (known != null) {
            return Target(known.uuid.toString(), known.username)
        }

        if (source != ServerFeatureSource.NODE) {
            return null
        }

        return serverManager.getLatestMetrics(serverId)
            ?.players
            ?.firstOrNull { it.username.equals(identifier, ignoreCase = true) }
            ?.let { Target(null, it.username) }
    }

    private fun sendThroughPlugin(
        server: Server,
        action: ServerPlayerAction,
        target: Target,
        text: String?,
        gamemode: String?,
        issuedBy: String
    ) {
        if (!serverManager.isConnected(server.id)) {
            throw ServerOffline()
        }

        val message = if (action.isPluginAction) {
            if (!server.hasCapability(ServerCapability.PLAYERS)) {
                throw ServerCapabilityMissing()
            }

            PlayerActionMessage(
                action = action.name,
                // The plugin addresses players by uuid, so an action on someone only the ping
                // knows about is not something it can be asked to do.
                uuid = target.uuid ?: throw ServerCapabilityMissing(),
                username = target.username,
                text = text,
                issuedBy = issuedBy
            )
        } else {
            if (!server.hasCapability(ServerCapability.COMMANDS)) {
                throw ServerCapabilityMissing()
            }

            ExecuteCommandMessage(
                command = ServerPlayerCommandComposer.compose(action, server.type, target.username, gamemode),
                requestId = UUID.randomUUID().toString(),
                issuedBy = issuedBy
            )
        }

        if (!serverManager.sendMessage(server.id, message)) {
            throw ServerOffline()
        }
    }

    private fun sendThroughNode(
        server: Server,
        action: ServerPlayerAction,
        target: Target,
        text: String?,
        gamemode: String?,
        issuedBy: String
    ) {
        val nodeId = server.nodeId ?: throw ServerCapabilityMissing()
        val uuid = server.uuid ?: throw ServerCapabilityMissing()

        val command = ServerPlayerCommandComposer.composeForConsole(
            action = action,
            serverType = server.type,
            username = target.username,
            text = text,
            gamemode = gamemode
        )

        if (!nodeManager.sendMessage(nodeId, SendCommandMessage(uuid, command, issuedBy))) {
            throw NodeOffline()
        }
    }

    companion object {
        /** Upper bound for a kick reason or a private message. */
        private const val MAX_TEXT_LENGTH = 512

        /** A BAN that reached the player's Pano account: the website and every server. */
        const val SCOPE_PANO = "pano"

        /** A BAN that is only this server's own ban list: the player has no Pano account. */
        const val SCOPE_SERVER = "server"
    }
}
