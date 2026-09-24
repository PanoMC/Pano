package com.panomc.platform.route.api.panel.server.players

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.panel.permission.ManageServerPlayersPermission
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.Server
import com.panomc.platform.error.NoPermission
import com.panomc.platform.model.*
import com.panomc.platform.server.ServerManager
import com.panomc.platform.server.players.ServerRosterBuilder
import io.vertx.core.json.JsonArray
import io.vertx.ext.web.RoutingContext
import io.vertx.json.schema.SchemaRepository
import com.panomc.platform.util.UsageMode

/**
 * Everyone who is in-game right now, across every server the caller may look after.
 *
 * The per-server roster is the live one; this is the overview that answers "where is everybody",
 * which is why the panel polls it rather than being pushed to.
 *
 * Permission is evaluated per server rather than once: `MANAGE_SERVER_PLAYERS` can be held
 * globally or scoped to individual servers, and somebody who only looks after two of ten servers
 * must see those two here and no more. A global holder skips the per-server pass entirely, both
 * because it would always pass and because that is the common case.
 */
@Endpoint
class PanelGetAllServerPlayersAPI(
    private val databaseManager: DatabaseManager,
    private val authProvider: AuthProvider,
    private val serverManager: ServerManager
) : PanelApi() {
    // Registered ahead of `/api/panel/servers/:id`, which would otherwise answer this with a
    // server called "players".
    override val order = 0

    override val usageModes = UsageMode.WITH_SERVERS


    override val paths = listOf(Path("/api/panel/servers/players", RouteType.GET))

    override fun getValidationHandler(schemaRepository: SchemaRepository) = null

    override suspend fun handle(context: RoutingContext): Result {
        val sqlClient = getSqlClient()

        val permission = ManageServerPlayersPermission()
        val holdsGlobally = authProvider.hasPermission(permission, context)

        val servers = databaseManager.serverDao.getAllByPermissionGranted(sqlClient)

        val allowed = if (holdsGlobally) {
            servers
        } else {
            servers.filter { authProvider.hasPermission(permission, context, it.id) }
        }

        // Nothing scoped and nothing global is a "no", not an empty roster: an empty answer would
        // tell somebody with no permission at all that there simply is nobody online.
        if (!holdsGlobally && allowed.isEmpty()) {
            throw NoPermission()
        }

        val players = JsonArray()

        allowed
            .sortedWith(compareBy<Server> { displayName(it).lowercase() }.thenBy { it.id })
            .forEach { server ->
                val roster = ServerRosterBuilder.build(
                    databaseManager.serverPlayerDao.getAllByServerId(server.id, sqlClient),
                    serverManager.getLatestMetrics(server.id)
                )

                roster.forEach { player ->
                    players.add(
                        player.copy()
                            .put("serverId", server.id)
                            .put("serverName", displayName(server))
                    )
                }
            }

        return Successful(mapOf("players" to players))
    }

    private fun displayName(server: Server) =
        server.customName?.takeIf { it.isNotBlank() } ?: server.name
}
