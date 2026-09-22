package com.panomc.platform.route.api.panel.server.console

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.PermissionManager
import com.panomc.platform.auth.panel.log.SentServerCommandLog
import com.panomc.platform.auth.panel.permission.ManageServerConsolePermission
import com.panomc.platform.auth.panel.permission.ManageServersPermission
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.Server
import com.panomc.platform.error.BadRequest
import com.panomc.platform.error.CommandDenied
import com.panomc.platform.error.NodeOffline
import com.panomc.platform.error.NotExists
import com.panomc.platform.error.RateLimited
import com.panomc.platform.error.ServerCapabilityMissing
import com.panomc.platform.error.ServerOffline
import com.panomc.platform.model.*
import com.panomc.platform.node.NodeManager
import com.panomc.platform.node.message.SendCommandMessage
import com.panomc.platform.server.ServerManager
import com.panomc.platform.server.console.CommandPolicy
import com.panomc.platform.server.console.ServerActionRateLimiter
import com.panomc.platform.server.feature.ServerFeature
import com.panomc.platform.server.feature.ServerFeatureResolver
import com.panomc.platform.server.feature.ServerFeatureSource
import com.panomc.platform.server.message.ExecuteCommandMessage
import io.vertx.core.json.JsonArray
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.RequestPredicate
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Bodies.json
import io.vertx.ext.web.validation.builder.Parameters.param
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.numberSchema
import io.vertx.json.schema.common.dsl.Schemas.objectSchema
import io.vertx.json.schema.common.dsl.Schemas.stringSchema
import java.util.UUID

/**
 * Runs one console command on a server.
 *
 * Two delivery paths, and which one is used is [ServerFeature.CONSOLE_INPUT]'s decision rather
 * than this endpoint's (SM-52). A node writes the command to the process's stdin, which is what
 * the server's own console does and therefore works before the game has finished loading and while
 * it is shutting down; the plugin dispatches it as the console sender, which is the only route
 * left when Pano cannot see the process — a linked server, or a managed one whose node is offline.
 *
 * A managed server whose process the node *adopted* after a restart has no stdin left (SM-51), and
 * that one case keeps its own 409 `SERVER_NO_STDIN` answer: "restart it from the panel" is a
 * different sentence from "nothing here can run a command".
 *
 * Either way the command is forwarded verbatim and never interpolated into anything, and no shell
 * is involved on either side. Because console access is effectively operator access, every
 * accepted command is written to the activity log and every user is rate limited per server.
 */
@Endpoint
class PanelSendServerCommandAPI(
    private val databaseManager: DatabaseManager,
    private val authProvider: AuthProvider,
    private val permissionManager: PermissionManager,
    private val serverManager: ServerManager,
    private val nodeManager: NodeManager,
    private val serverActionRateLimiter: ServerActionRateLimiter,
    private val serverFeatureResolver: ServerFeatureResolver
) : PanelApi() {
    override val paths = listOf(Path("/api/panel/servers/:id/console/command", RouteType.POST))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .pathParameter(param("id", numberSchema()))
            .body(
                json(
                    objectSchema()
                        .requiredProperty("command", stringSchema())
                )
            )
            .predicate(RequestPredicate.BODY_REQUIRED)
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        val parameters = getParameters(context)
        val id = parameters.pathParameter("id").long

        authProvider.requirePermission(ManageServerConsolePermission(), context, id)

        val command = parameters.body().jsonObject.getString("command").trim()

        if (command.isEmpty() || command.toByteArray(Charsets.UTF_8).size > MAX_COMMAND_BYTES) {
            throw BadRequest()
        }

        val sqlClient = getSqlClient()

        val server = databaseManager.serverDao.getById(id, sqlClient) ?: throw NotExists()

        if (!server.permissionGranted) {
            throw NotExists()
        }

        // Before the policy and the limiter: a command nothing can deliver should cost the caller
        // neither their budget nor an activity-log line.
        val source = serverFeatureResolver.pick(server, ServerFeature.CONSOLE_INPUT)

        val userId = authProvider.getUserIdFromRoutingContext(context)

        // Before the limiter, so a refused command does not also spend the caller's budget: the
        // policy is a permanent "not this word", not a "not so fast".
        refuseDeniedCommand(context, userId, id, command)

        if (!serverActionRateLimiter.tryAcquire(ServerActionRateLimiter.Action.CONSOLE_COMMAND, userId, id)) {
            throw RateLimited()
        }

        val username = databaseManager.userDao.getUsernameFromUserId(userId, sqlClient) ?: throw NotExists()

        when (source) {
            ServerFeatureSource.NODE -> sendToNode(server, command, username)
            // PANO can never win console.input -- Pano's own ring buffer only reads -- so
            // anything that is not the node is the plugin.
            else -> sendToPlugin(server, command, username)
        }

        databaseManager.panelActivityLogDao.add(
            SentServerCommandLog(userId, username, id, command),
            sqlClient
        )

        return Successful()
    }

    /**
     * Applies the caller's console grants' deny policies to [command] (§2.4.12).
     *
     * Every applicable grant is consulted rather than the "best" one: a policy is a restriction,
     * and a user who is denied `op` through their group must not be able to escape it by also
     * holding a second, unrestricted grant — the union of the deny lists is what applies.
     *
     * Admins bypass. Someone holding `*` can edit the permission grid itself, so a policy that
     * stopped them would be a speed bump with an obvious detour rather than a security boundary.
     */
    private suspend fun refuseDeniedCommand(
        context: RoutingContext,
        userId: Long,
        serverId: Long,
        command: String
    ) {
        if (context.get<Boolean>("isAdmin") == true || authProvider.isUserAdmin(userId)) {
            return
        }

        val patterns = POLICY_NODES
            .flatMap { permissionManager.getApplicableNodes(userId, it, serverId) }
            .distinctBy { it.id }
            .flatMap { readPatterns(it.context.getValue(CommandPolicy.CONTEXT_KEY)) }

        val denied = CommandPolicy.deniedPattern(patterns, command) ?: return

        throw CommandDenied(extras = mapOf("pattern" to denied))
    }

    /** A policy stored as an array, as a single string, or as a comma separated one. */
    private fun readPatterns(value: Any?): List<String> = when (value) {
        is JsonArray -> value.mapNotNull { it as? String }
        is String -> value.split(",")
        else -> emptyList()
    }.map { it.trim() }.filter { it.isNotEmpty() }

    private fun sendToNode(server: Server, command: String, username: String) {
        val nodeId = server.nodeId ?: throw ServerCapabilityMissing()
        val uuid = server.uuid ?: throw ServerCapabilityMissing()

        if (!nodeManager.isConnected(nodeId)) {
            throw NodeOffline()
        }

        val sent = nodeManager.sendMessage(nodeId, SendCommandMessage(uuid, command, username))

        if (!sent) {
            throw NodeOffline()
        }
    }

    private fun sendToPlugin(server: Server, command: String, username: String) {
        if (!serverManager.isConnected(server.id)) {
            throw ServerOffline()
        }

        val sent = serverManager.sendMessage(
            server.id,
            ExecuteCommandMessage(
                command = command,
                requestId = UUID.randomUUID().toString(),
                issuedBy = username
            )
        )

        if (!sent) {
            throw ServerOffline()
        }
    }

    companion object {
        /** Commands longer than this are rejected outright, matching the console contract. */
        private const val MAX_COMMAND_BYTES = 1024

        /**
         * The grants whose `context.denyCommands` this endpoint reads.
         *
         * Sending a command is authorised by `MANAGE_SERVER_CONSOLE` or by the `MANAGE_SERVERS`
         * umbrella that implies it, so those are the two nodes a policy can meaningfully hang
         * off — the panel's editor offers the chip input on exactly the same pair.
         */
        private val POLICY_NODES = listOf(
            ManageServerConsolePermission().toString(),
            ManageServersPermission().toString()
        )
    }
}
