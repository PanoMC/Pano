package com.panomc.platform.route.api.server

import com.panomc.platform.ErrorCode
import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.db.DatabaseManager
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
            return Error(ErrorCode.INSTALLATION_REQUIRED)
        }

        if (!serverAuthProvider.isAuthenticated(context)) {
            return Error(ErrorCode.INVALID_TOKEN)
        }

        val serverId = serverAuthProvider.getServerIdFromRoutingContext(context)

        val sqlConnection = createConnection(context)

        val exists = databaseManager.serverDao.existsById(serverId, sqlConnection)

        if (!exists) {
            return Error(ErrorCode.INVALID_TOKEN)
        }

        val mainServerId = databaseManager.systemPropertyDao.getByOption(
            "main_server",
            sqlConnection
        )!!.value.toLong()

        if (mainServerId == serverId) {
            databaseManager.systemPropertyDao.update(
                "main_server",
                "-1",
                sqlConnection
            )
        }

        databaseManager.serverPlayerDao.deleteByServerId(serverId, sqlConnection)

        databaseManager.serverDao.deleteById(serverId, sqlConnection)

        return Successful()
    }
}