package com.panomc.platform.route.api.panel.permission

import com.panomc.platform.ErrorCode
import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.model.*
import com.panomc.platform.util.AuthProvider
import com.panomc.platform.util.SetupManager
import io.vertx.ext.web.RoutingContext

@Endpoint
class PermissionDeleteGroupAPI(
    private val databaseManager: DatabaseManager,
    setupManager: SetupManager,
    authProvider: AuthProvider
) : PanelApi(setupManager, authProvider) {
    override val routeType = RouteType.POST

    override val routes = arrayListOf("/api/panel/permission/delete/group")

    override suspend fun handler(context: RoutingContext): Result {
        val data = context.bodyAsJson

        val permissionGroupID = data.getInteger("id")

        val sqlConnection = createConnection(databaseManager, context)

        val isTherePermissionGroup =
            databaseManager.permissionGroupDao.isThereByID(permissionGroupID, sqlConnection)

        if (!isTherePermissionGroup) {
            throw Error(ErrorCode.NOT_EXISTS)
        }

        val permissionGroup =
            databaseManager.permissionGroupDao.getPermissionGroupByID(permissionGroupID, sqlConnection)
                ?: throw Error(ErrorCode.UNKNOWN)

        if (permissionGroup.name == "admin") {
            throw Error(ErrorCode.CANT_UPDATE_ADMIN_PERMISSION)
        }

        databaseManager.permissionGroupPermsDao.removePermissionGroup(permissionGroupID, sqlConnection)

        databaseManager.userDao.removePermissionGroupByPermissionGroupID(permissionGroupID, sqlConnection)

        databaseManager.permissionGroupDao.deleteByID(permissionGroupID, sqlConnection)

        return Successful()
    }
}