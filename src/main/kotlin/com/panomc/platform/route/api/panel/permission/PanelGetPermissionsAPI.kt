package com.panomc.platform.route.api.panel.permission

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.model.*
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaParser

@Endpoint
class PanelGetPermissionsAPI(
    private val databaseManager: DatabaseManager
) : PanelApi() {
    override val paths = listOf(Path("/api/panel/permissions", RouteType.GET))

    override fun getValidationHandler(schemaParser: SchemaParser): ValidationHandler =
        ValidationHandlerBuilder.create(schemaParser)
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        val result = mutableMapOf<String, Any?>()

        val sqlConnection = createConnection(context)

        val permissions = databaseManager.permissionDao.getPermissions(sqlConnection)

        result["permissions"] = permissions

        return Successful(result)
    }
}