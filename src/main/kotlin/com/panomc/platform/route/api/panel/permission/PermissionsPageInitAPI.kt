package com.panomc.platform.route.api.panel.permission

import com.panomc.platform.ErrorCode
import com.panomc.platform.db.model.Permission
import com.panomc.platform.db.model.PermissionGroup
import com.panomc.platform.db.model.PermissionGroupPerms
import com.panomc.platform.model.*
import io.vertx.core.AsyncResult
import io.vertx.ext.web.RoutingContext
import io.vertx.sqlclient.SqlConnection

class PermissionsPageInitAPI : PanelApi() {
    override val routeType = RouteType.GET

    override val routes = arrayListOf("/api/panel/initPage/permissionsPage")

    override fun handler(context: RoutingContext, handler: (result: Result) -> Unit) {
        databaseManager.createConnection((this::createConnectionHandler)(handler))
    }

    private fun createConnectionHandler(
        handler: (result: Result) -> Unit
    ) = handler@{ sqlConnection: SqlConnection?, _: AsyncResult<SqlConnection> ->
        if (sqlConnection == null) {
            handler.invoke(Error(ErrorCode.CANT_CONNECT_DATABASE))

            return@handler
        }

        databaseManager.getDatabase().permissionDao.getPermissions(
            sqlConnection,
            (this::getPermissionsHandler)(sqlConnection, handler)
        )
    }

    private fun getPermissionsHandler(
        sqlConnection: SqlConnection,
        handler: (result: Result) -> Unit
    ) = handler@{ permissions: List<Permission>?, _: AsyncResult<*> ->
        if (permissions == null) {
            databaseManager.closeConnection(sqlConnection) {
                handler.invoke(Error(ErrorCode.UNKNOWN))
            }

            return@handler
        }

        val result = mutableMapOf<String, Any?>()

        result["permissions"] = permissions

        databaseManager.getDatabase().permissionGroupDao.getPermissionGroups(
            sqlConnection,
            (this::getPermissionGroupsHandler)(sqlConnection, handler, result)
        )
    }

    private fun getPermissionGroupsHandler(
        sqlConnection: SqlConnection,
        handler: (result: Result) -> Unit,
        result: MutableMap<String, Any?>
    ) = handler@{ permissionGroups: List<PermissionGroup>?, _: AsyncResult<*> ->
        if (permissionGroups == null) {
            databaseManager.closeConnection(sqlConnection) {
                handler.invoke(Error(ErrorCode.UNKNOWN))
            }

            return@handler
        }

        val permissionGroupsList: List<MutableMap<String, Any?>> = permissionGroups.map { permissionGroup ->
            mutableMapOf(
                "id" to permissionGroup.id,
                "name" to permissionGroup.name
            )
        }

        val handlers: List<(handler: () -> Unit) -> Any> =
            permissionGroups.map { permissionGroup ->
                val localHandler: (handler: () -> Unit) -> Any = { localHandler ->
                    databaseManager.getDatabase().userDao.getCountOfUsersByPermissionGroupID(
                        permissionGroup.id,
                        sqlConnection,
                        (this::getCountOfUsersByPermissionGroupIDHandler)(
                            sqlConnection,
                            handler,
                            permissionGroup,
                            permissionGroupsList,
                            localHandler
                        )
                    )
                }

                localHandler
            }

        var currentIndex = -1

        fun invoke() {
            val localHandler: () -> Unit = {
                if (currentIndex == handlers.lastIndex) {
                    result["permission_groups"] = permissionGroupsList

                    databaseManager.getDatabase().permissionGroupPermsDao.getPermissionGroupPerms(
                        sqlConnection,
                        (this::getPermissionGroupPermsHandler)(sqlConnection, handler, result)
                    )
                } else
                    invoke()
            }

            currentIndex++

            if (currentIndex <= handlers.lastIndex)
                handlers[currentIndex].invoke(localHandler)
        }

        invoke()
    }

    private fun getCountOfUsersByPermissionGroupIDHandler(
        sqlConnection: SqlConnection,
        handler: (result: Result) -> Unit,
        permissionGroup: PermissionGroup,
        permissionGroupList: List<MutableMap<String, Any?>>,
        localHandler: () -> Unit
    ) = handler@{ count: Int?, _: AsyncResult<*> ->
        if (count == null) {
            databaseManager.closeConnection(sqlConnection) {
                handler.invoke(Error(ErrorCode.UNKNOWN))
            }

            return@handler
        }

        permissionGroupList.find { it["id"] == permissionGroup.id }!!["user_count"] = count

        databaseManager.getDatabase().userDao.getUsernamesByPermissionGroupID(
            permissionGroup.id,
            3,
            sqlConnection,
            (this::getUsernamesByPermissionGroupIDHandler)(
                sqlConnection,
                handler,
                permissionGroup,
                permissionGroupList,
                localHandler
            )
        )
    }

    private fun getUsernamesByPermissionGroupIDHandler(
        sqlConnection: SqlConnection,
        handler: (result: Result) -> Unit,
        permissionGroup: PermissionGroup,
        permissionGroupList: List<MutableMap<String, Any?>>,
        localHandler: () -> Unit
    ) = handler@{ usernameList: List<String>?, _: AsyncResult<*> ->
        if (usernameList == null) {
            databaseManager.closeConnection(sqlConnection) {
                handler.invoke(Error(ErrorCode.UNKNOWN))
            }

            return@handler
        }

        permissionGroupList.find { it["id"] == permissionGroup.id }!!["users"] = usernameList

        localHandler.invoke()
    }

    private fun getPermissionGroupPermsHandler(
        sqlConnection: SqlConnection,
        handler: (result: Result) -> Unit,
        result: MutableMap<String, Any?>
    ) = handler@{ permissionGroupPerms: List<PermissionGroupPerms>?, _: AsyncResult<*> ->
        databaseManager.closeConnection(sqlConnection) {
            if (permissionGroupPerms == null) {
                handler.invoke(Error(ErrorCode.UNKNOWN))

                return@closeConnection
            }

            val permissionGroupPermIDListMap = permissionGroupPerms
                .distinctBy { it.permissionGroupID }
                .associateBy({ it.permissionGroupID }, { mutableListOf<Int>() })

            permissionGroupPerms.forEach { perm ->
                permissionGroupPermIDListMap[perm.permissionGroupID]!!.add(perm.permissionID)
            }

            result["permission_group_perms"] = permissionGroupPermIDListMap

            handler.invoke(Successful(result))
        }
    }
}