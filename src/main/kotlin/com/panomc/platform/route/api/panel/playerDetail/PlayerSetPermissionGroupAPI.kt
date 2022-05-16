package com.panomc.platform.route.api.panel.playerDetail

import com.panomc.platform.ErrorCode
import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.PermissionGroup
import com.panomc.platform.model.*
import com.panomc.platform.util.AuthProvider
import com.panomc.platform.util.SetupManager
import io.vertx.ext.web.RoutingContext

@Endpoint
class PlayerSetPermissionGroupAPI(
    private val databaseManager: DatabaseManager,
    setupManager: SetupManager,
    authProvider: AuthProvider
) : PanelApi(setupManager, authProvider) {
    override val routeType = RouteType.POST

    override val routes = arrayListOf("/api/panel/player/set/permissionGroup")

    override suspend fun handler(context: RoutingContext): Result {
        val data = context.bodyAsJson

        val username = data.getString("username")
        val permissionGroup = data.getString("permissionGroup")

        val sqlConnection = createConnection(databaseManager, context)

        val exists = databaseManager.userDao.isExistsByUsername(username, sqlConnection)

        if (!exists) {
            throw Error(ErrorCode.NOT_EXISTS)
        }

        var permissionGroupID = 0

        if (permissionGroup != "-") {
            val isTherePermissionGroup = databaseManager.permissionGroupDao.isThere(
                PermissionGroup(-1, permissionGroup),
                sqlConnection
            )

            if (!isTherePermissionGroup) {
                throw Error(ErrorCode.NOT_EXISTS)
            }

            permissionGroupID = databaseManager.permissionGroupDao.getPermissionGroupID(
                PermissionGroup(-1, permissionGroup),
                sqlConnection
            ) ?: throw Error(ErrorCode.UNKNOWN)
        }

        val userPermissionGroupId =
            databaseManager.userDao.getPermissionGroupIDFromUsername(username, sqlConnection) ?: throw Error(
                ErrorCode.UNKNOWN
            )

        if (userPermissionGroupId == -1) {
            databaseManager.userDao.setPermissionGroupByUsername(
                permissionGroupID,
                username,
                sqlConnection
            )

            return Successful()
        }

        val userPermissionGroup =
            databaseManager.permissionGroupDao.getPermissionGroupByID(userPermissionGroupId, sqlConnection)
                ?: throw Error(ErrorCode.UNKNOWN)

        if (userPermissionGroup.name == "admin") {
            val count = databaseManager.userDao.getCountOfUsersByPermissionGroupID(
                userPermissionGroupId,
                sqlConnection
            )

            if (count == 1) {
                throw Errors(mapOf("LAST_ADMIN" to true))
            }
        }

        databaseManager.userDao.setPermissionGroupByUsername(
            permissionGroupID,
            username,
            sqlConnection
        )

        return Successful()
    }
}