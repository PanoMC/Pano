package com.panomc.platform.route.api.panel.permission

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.PermissionGroup
import com.panomc.platform.model.*
import com.panomc.platform.util.AuthProvider
import com.panomc.platform.util.SetupManager
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Bodies
import io.vertx.json.schema.SchemaParser
import io.vertx.json.schema.common.dsl.Schemas

@Endpoint
class PanelAddPermissionGroupAPI(
    private val databaseManager: DatabaseManager,
    setupManager: SetupManager,
    authProvider: AuthProvider
) : PanelApi(setupManager, authProvider) {
    override val routeType = RouteType.POST

    override val routes = arrayListOf("/api/panel/permissionGroups")

    override fun getValidationHandler(schemaParser: SchemaParser): ValidationHandler =
        ValidationHandler.builder(schemaParser)
            .body(
                Bodies.json(
                    Schemas.objectSchema()
                        .property("name", Schemas.stringSchema())
                )
            )
            .build()

    override suspend fun handler(context: RoutingContext): Result {
        val parameters = getParameters(context)
        val data = parameters.body().jsonObject

        var name = data.getString("name")

        validateForm(name)

        name = getSystematicName(name)

        val sqlConnection = createConnection(databaseManager, context)

        val isTherePermissionGroup =
            databaseManager.permissionGroupDao.isThereByName(name, sqlConnection)

        if (isTherePermissionGroup) {
            throw Errors(mapOf("name" to true))
        }

        databaseManager.permissionGroupDao.add(PermissionGroup(name = name), sqlConnection)

        return Successful()
    }

    private fun validateForm(
        name: String
    ) {
        val errors = mutableMapOf<String, Boolean>()

        if (name.isEmpty() || name.length > 32)
            errors["name"] = true

        if (errors.isNotEmpty()) {
            throw Errors(errors)
        }
    }

    private fun getSystematicName(name: String) = name.lowercase().replace("\\s+".toRegex(), "-")
}