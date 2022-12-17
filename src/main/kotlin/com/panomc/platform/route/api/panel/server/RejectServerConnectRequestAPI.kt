package com.panomc.platform.route.api.panel.server

import com.panomc.platform.ErrorCode
import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.model.*
import com.panomc.platform.server.ServerManager
import com.panomc.platform.util.AuthProvider
import com.panomc.platform.util.SetupManager
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Parameters.param
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaParser
import io.vertx.json.schema.common.dsl.Schemas.numberSchema

@Endpoint
class RejectServerConnectRequestAPI(
    private val databaseManager: DatabaseManager,
    setupManager: SetupManager,
    authProvider: AuthProvider,
    private val serverManager: ServerManager
) : PanelApi(setupManager, authProvider) {
    override val paths = listOf(Path("/api/panel/servers/:id/reject", RouteType.POST))

    override fun getValidationHandler(schemaParser: SchemaParser): ValidationHandler =
        ValidationHandlerBuilder.create(schemaParser)
            .pathParameter(param("id", numberSchema()))
            .build()

    override suspend fun handler(context: RoutingContext): Result {
        val parameters = getParameters(context)
        val id = parameters.pathParameter("id").long

        val sqlConnection = createConnection(databaseManager, context)

        val exists = databaseManager.serverDao.existsById(id, sqlConnection)

        if (!exists) {
            return Error(ErrorCode.NOT_EXISTS)
        }

        if (serverManager.isConnected(id)) {
            serverManager.closeConnection(id)
        }

        databaseManager.serverDao.deleteById(id, sqlConnection)

        return Successful()
    }
}