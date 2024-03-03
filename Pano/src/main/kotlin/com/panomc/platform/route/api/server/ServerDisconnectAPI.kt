package com.panomc.platform.route.api.server

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.error.InstallationRequired
import com.panomc.platform.error.InvalidToken
import com.panomc.platform.model.*
import com.panomc.platform.server.ServerAuthProvider
import com.panomc.platform.setup.SetupManager
import io.vertx.ext.web.RoutingContext
import io.vertx.json.schema.SchemaParser

@Endpoint
class ServerDisconnectAPI(
    private val databaseManager: DatabaseManager,
    private val setupManager: SetupManager,
    private val serverAuthProvider: ServerAuthProvider
) : Api() {
    override val paths = listOf(Path("/api/server/disconnect", RouteType.POST))

    override fun getValidationHandler(schemaParser: SchemaParser) = null

    override suspend fun handle(context: RoutingContext): Result {
        if (!setupManager.isSetupDone()) {
            return InstallationRequired()
        }

        if (!serverAuthProvider.isAuthenticated(context)) {
            return InvalidToken()
        }

        val serverId = serverAuthProvider.getServerIdFromRoutingContext(context)

        val sqlClient = getSqlClient()

        val exists = databaseManager.serverDao.existsById(serverId, sqlClient)

        if (!exists) {
            return InvalidToken()
        }

        val mainServerId = databaseManager.systemPropertyDao.getByOption(
            "main_server",
            sqlClient
        )!!.value.toLong()

        if (mainServerId == serverId) {
            databaseManager.systemPropertyDao.update(
                "main_server",
                "-1",
                sqlClient
            )
        }

        databaseManager.serverPlayerDao.deleteByServerId(serverId, sqlClient)

        databaseManager.serverDao.deleteById(serverId, sqlClient)

        return Successful()
    }
}