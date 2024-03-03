package com.panomc.platform.route.api.panel.server


import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.PanelPermission
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.error.NotExists
import com.panomc.platform.model.*
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Parameters.param
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaParser
import io.vertx.json.schema.common.dsl.Schemas.numberSchema

@Endpoint
class PanelAcceptServerConnectRequestAPI(
    private val databaseManager: DatabaseManager,
    private val authProvider: AuthProvider
) : PanelApi() {
    override val paths = listOf(Path("/api/panel/servers/:id/accept", RouteType.POST))

    override fun getValidationHandler(schemaParser: SchemaParser): ValidationHandler =
        ValidationHandlerBuilder.create(schemaParser)
            .pathParameter(param("id", numberSchema()))
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        authProvider.requirePermission(PanelPermission.MANAGE_SERVERS, context)

        val parameters = getParameters(context)
        val id = parameters.pathParameter("id").long

        val sqlClient = getSqlClient()

        val exists = databaseManager.serverDao.existsById(id, sqlClient)

        if (!exists) {
            throw NotExists()
        }

        databaseManager.serverDao.updatePermissionGrantedById(id, true, sqlClient)
        databaseManager.serverDao.updateAcceptedTimeById(id, System.currentTimeMillis(), sqlClient)

        val mainServerId = databaseManager.systemPropertyDao.getByOption(
            "main_server",
            sqlClient
        )!!.value.toLong()

        if (mainServerId == -1L) {
            databaseManager.systemPropertyDao.update(
                "main_server",
                id.toString(),
                sqlClient
            )
        }

        return Successful()
    }
}