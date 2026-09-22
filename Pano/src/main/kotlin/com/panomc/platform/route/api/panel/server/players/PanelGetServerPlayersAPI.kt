package com.panomc.platform.route.api.panel.server.players

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.panel.permission.ManageServerPlayersPermission
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.error.NotExists
import com.panomc.platform.model.*
import com.panomc.platform.server.ServerCapability
import com.panomc.platform.server.ServerManager
import com.panomc.platform.server.feature.ServerFeatureResolver
import com.panomc.platform.server.feature.ServerFeatureSource
import com.panomc.platform.server.players.ServerRosterBuilder
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Parameters.param
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.numberSchema

/**
 * The live roster of a server, from whichever source can see it (SM-52, §2.4.17).
 *
 * The plugin's list is the real one: it is written by the join and quit events and carries uuids,
 * pings and session times. A server with no plugin in it still gets a list, from the server list
 * ping its node performs every ten seconds — an exact count and up to twelve names, and the
 * response says so in `quality` so the panel can label it rather than quietly showing a short
 * server as a complete one.
 *
 * A server nothing can see answers with an empty list and a null `source`, which is what disables
 * the page's controls, instead of failing: an empty roster is a legitimate answer to "who is on".
 */
@Endpoint
class PanelGetServerPlayersAPI(
    private val databaseManager: DatabaseManager,
    private val authProvider: AuthProvider,
    private val serverManager: ServerManager,
    private val serverFeatureResolver: ServerFeatureResolver
) : PanelApi() {
    override val paths = listOf(Path("/api/panel/servers/:id/players", RouteType.GET))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .pathParameter(param("id", numberSchema()))
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        val parameters = getParameters(context)
        val id = parameters.pathParameter("id").long

        authProvider.requirePermission(ManageServerPlayersPermission(), context, id)

        val sqlClient = getSqlClient()

        val server = databaseManager.serverDao.getById(id, sqlClient) ?: throw NotExists()

        if (!server.permissionGranted) {
            throw NotExists()
        }

        val features = serverFeatureResolver.resolve(server)
        val sample = serverManager.getLatestMetrics(id)

        val players = when (features.players.list) {
            ServerFeatureSource.PLUGIN -> {
                val rows = databaseManager.serverPlayerDao.getAllByServerId(id, sqlClient)

                ServerRosterBuilder.build(
                    rows,
                    sample,
                    ServerRosterBuilder.panoUsernames(rows.map { it.username }, databaseManager.userDao, sqlClient)
                )
            }

            ServerFeatureSource.NODE -> ServerRosterBuilder.fromSample(
                sample,
                ServerRosterBuilder.panoUsernames(
                    sample?.players.orEmpty().map { it.username },
                    databaseManager.userDao,
                    sqlClient
                )
            )

            else -> emptyList()
        }

        return Successful(
            mapOf(
                "players" to players,
                "online" to serverManager.isConnected(id),
                "capable" to server.hasCapability(ServerCapability.PLAYERS),
                "source" to features.players.list?.id,
                "quality" to features.players.listQuality,
                "actions" to features.players.actions?.id,
                // The ping knows how many are on even when it only names twelve of them.
                "playerCount" to (sample?.playerCount ?: server.playerCount),
                "maxPlayerCount" to (sample?.maxPlayerCount ?: server.maxPlayerCount)
            )
        )
    }
}
