package com.panomc.platform.route.api.panel.permission

import com.panomc.platform.ErrorCode
import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.model.*
import com.panomc.platform.util.AuthProvider
import com.panomc.platform.util.SetupManager
import io.vertx.ext.web.RoutingContext

@Endpoint
class PermissionSetAPI(
    private val databaseManager: DatabaseManager,
    setupManager: SetupManager,
    authProvider: AuthProvider
) : PanelApi(setupManager, authProvider) {
    override val routeType = RouteType.POST

    override val routes = arrayListOf("/api/panel/permission/set")

    override suspend fun handler(context: RoutingContext): Result {
        val data = context.bodyAsJson

        val permissionGroupID = data.getInteger("permissionGroupId")
        val permissionID = data.getInteger("permissionId")
        val mode = data.getString("mode")

        if (mode != "ADD" && mode != "DELETE") {
            throw Error(ErrorCode.UNKNOWN)
        }

        val sqlConnection = createConnection(databaseManager, context)

        val isTherePermission = databaseManager.permissionDao.isTherePermissionByID(permissionID, sqlConnection)

        if (!isTherePermission) {
            throw Error(ErrorCode.NOT_EXISTS)
        }

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

        val doesPermissionGroupHavePermission =
            databaseManager.permissionGroupPermsDao.doesPermissionGroupHavePermission(
                permissionGroupID,
                permissionID,
                sqlConnection
            )


        if (mode == "ADD" && !doesPermissionGroupHavePermission)
            databaseManager.permissionGroupPermsDao.addPermission(permissionGroupID, permissionID, sqlConnection)
        else if (doesPermissionGroupHavePermission)
            databaseManager.permissionGroupPermsDao.removePermission(permissionGroupID, permissionID, sqlConnection)


        return Successful()
    }
}