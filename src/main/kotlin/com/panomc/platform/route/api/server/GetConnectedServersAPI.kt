package com.panomc.platform.route.api.server

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.model.*
import com.panomc.platform.util.AuthProvider
import com.panomc.platform.util.SetupManager
import io.vertx.ext.web.RoutingContext
import io.vertx.json.schema.SchemaParser

@Endpoint
class GetConnectedServersAPI(
    private val databaseManager: DatabaseManager,
    setupManager: SetupManager,
    authProvider: AuthProvider
) : LoggedInApi(setupManager, authProvider) {
    override val paths = listOf(Path("/api/servers", RouteType.GET))

    override fun getValidationHandler(schemaParser: SchemaParser) = null

    override suspend fun handler(context: RoutingContext): Result {
        val sqlConnection = createConnection(databaseManager, context)

        val servers = databaseManager.serverDao.getAllByPermissionGranted(sqlConnection)

        return Successful(
            mapOf(
                "servers" to servers
            )
        )
    }
}