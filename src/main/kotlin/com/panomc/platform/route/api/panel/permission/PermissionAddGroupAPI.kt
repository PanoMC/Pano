package com.panomc.platform.route.api.panel.permission

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.PermissionGroup
import com.panomc.platform.model.*
import com.panomc.platform.util.AuthProvider
import com.panomc.platform.util.SetupManager
import io.vertx.ext.web.RoutingContext

@Endpoint
class PermissionAddGroupAPI(
    private val databaseManager: DatabaseManager,
    setupManager: SetupManager,
    authProvider: AuthProvider
) : PanelApi(setupManager, authProvider) {
    override val routeType = RouteType.POST

    override val routes = arrayListOf("/api/panel/permission/add/group")

    override suspend fun handler(context: RoutingContext): Result {
        val data = context.bodyAsJson

        var name = data.getString("name")

        validateForm(name)

        name = getSystematicName(name)

        val sqlConnection = createConnection(databaseManager, context)

        val isTherePermissionGroup =
            databaseManager.permissionGroupDao.isThere(PermissionGroup(-1, name), sqlConnection)

        if (isTherePermissionGroup) {
            throw Errors(mapOf("name" to true))
        }

        databaseManager.permissionGroupDao.add(PermissionGroup(-1, name), sqlConnection)

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