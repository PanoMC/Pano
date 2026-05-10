package com.panomc.platform.route.api.panel.server


import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.panel.permission.ManageServersPermission
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.PanelConfig
import com.panomc.platform.error.NotExists
import com.panomc.platform.model.*
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.RequestPredicate
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Bodies.json
import io.vertx.ext.web.validation.builder.Parameters.param
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.*

@Endpoint
class PanelAcceptServerConnectRequestAPI(
    private val databaseManager: DatabaseManager,
    private val authProvider: AuthProvider
) : PanelApi() {
    override val paths = listOf(Path("/api/panel/servers/:id/accept", RouteType.POST))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .pathParameter(param("id", numberSchema()))
            .body(
                json(
                    objectSchema()
                        .optionalProperty(
                            "customName",
                            stringSchema().nullable().withKeyword("maxLength", 255)
                        )
                )
            )
            .predicate(RequestPredicate.BODY_REQUIRED)
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        authProvider.requirePermission(ManageServersPermission(), context)

        val parameters = getParameters(context)
        val data = parameters.body().jsonObject
        val id = parameters.pathParameter("id").long

        val userId = authProvider.getUserIdFromRoutingContext(context)

        val sqlClient = getSqlClient()

        val exists = databaseManager.serverDao.existsById(id, sqlClient)

        if (!exists) {
            throw NotExists()
        }

        databaseManager.serverDao.updatePermissionGrantedById(id, true, sqlClient)
        databaseManager.serverDao.updateAcceptedTimeById(id, System.currentTimeMillis(), sqlClient)

        if (data.containsKey("customName")) {
            val rawValue = data.getValue("customName")
            val normalized =
                when (rawValue) {
                    null -> null
                    is String -> rawValue.trim().take(255).takeIf { it.isNotEmpty() }
                    else -> null
                }
            databaseManager.serverDao.updateCustomNameById(id, normalized, sqlClient)
        }

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

        val panelConfig = databaseManager.panelConfigDao.byUserIdAndOption(userId, "selected_server", sqlClient)
        val value = id.toString()

        if (panelConfig != null) {
            databaseManager.panelConfigDao.updateValueById(panelConfig.id, value, sqlClient)
        } else {
            databaseManager.panelConfigDao.add(
                PanelConfig(
                    userId = userId,
                    option = "selected_server",
                    value = value
                ),
                sqlClient
            )
        }

        return Successful(mapOf("selected" to true))
    }
}