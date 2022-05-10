package com.panomc.platform.route.api.panel.permission

import com.panomc.platform.ErrorCode
import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.PermissionGroup
import com.panomc.platform.model.*
import com.panomc.platform.util.AuthProvider
import com.panomc.platform.util.SetupManager
import io.vertx.core.AsyncResult
import io.vertx.ext.web.RoutingContext
import io.vertx.sqlclient.SqlConnection

@Endpoint
class PermissionSetAPI(
    private val databaseManager: DatabaseManager,
    setupManager: SetupManager,
    authProvider: AuthProvider
) : PanelApi(setupManager, authProvider) {
    override val routeType = RouteType.POST

    override val routes = arrayListOf("/api/panel/permission/set")

    override fun handler(context: RoutingContext, handler: (result: Result) -> Unit) {
        val data = context.bodyAsJson

        val permissionGroupID = data.getInteger("permissionGroupId")
        val permissionID = data.getInteger("permissionId")
        val mode = data.getString("mode")

        if (mode != "ADD" && mode != "DELETE") {
            handler.invoke(Error(ErrorCode.UNKNOWN))

            return
        }

        databaseManager.createConnection(
            (this::createConnectionHandler)(
                handler,
                permissionGroupID,
                permissionID,
                mode
            )
        )
    }

    private fun createConnectionHandler(
        handler: (result: Result) -> Unit,
        permissionGroupID: Int,
        permissionID: Int,
        mode: String
    ) = handler@{ sqlConnection: SqlConnection?, _: AsyncResult<SqlConnection> ->
        if (sqlConnection == null) {
            handler.invoke(Error(ErrorCode.CANT_CONNECT_DATABASE))

            return@handler
        }

        databaseManager.permissionDao.isTherePermissionByID(
            permissionID,
            sqlConnection,
            (this::isTherePermissionByIDHandler)(sqlConnection, handler, permissionGroupID, permissionID, mode)
        )
    }

    private fun isTherePermissionByIDHandler(
        sqlConnection: SqlConnection,
        handler: (result: Result) -> Unit,
        permissionGroupID: Int,
        permissionID: Int,
        mode: String
    ) = handler@{ isTherePermission: Boolean?, _: AsyncResult<*> ->
        if (isTherePermission == null) {
            databaseManager.closeConnection(sqlConnection) {
                handler.invoke(Error(ErrorCode.UNKNOWN))
            }

            return@handler
        }

        if (!isTherePermission) {
            databaseManager.closeConnection(sqlConnection) {
                handler.invoke(Error(ErrorCode.NOT_EXISTS))
            }

            return@handler
        }

        databaseManager.permissionGroupDao.isThereByID(
            permissionGroupID,
            sqlConnection,
            (this::isThereByIDHandler)(sqlConnection, handler, permissionGroupID, permissionID, mode)
        )
    }

    private fun isThereByIDHandler(
        sqlConnection: SqlConnection,
        handler: (result: Result) -> Unit,
        permissionGroupID: Int,
        permissionID: Int,
        mode: String
    ) = handler@{ isTherePermissionGroup: Boolean?, _: AsyncResult<*> ->
        if (isTherePermissionGroup == null) {
            databaseManager.closeConnection(sqlConnection) {
                handler.invoke(Error(ErrorCode.UNKNOWN))
            }

            return@handler
        }

        if (!isTherePermissionGroup) {
            databaseManager.closeConnection(sqlConnection) {
                handler.invoke(Error(ErrorCode.NOT_EXISTS))
            }

            return@handler
        }

        databaseManager.permissionGroupDao.getPermissionGroupByID(
            permissionGroupID,
            sqlConnection,
            (this::getPermissionGroupByIDHandler)(sqlConnection, handler, permissionGroupID, permissionID, mode)
        )
    }

    private fun getPermissionGroupByIDHandler(
        sqlConnection: SqlConnection,
        handler: (result: Result) -> Unit,
        permissionGroupID: Int,
        permissionID: Int,
        mode: String
    ) = handler@{ permissionGroup: PermissionGroup?, _: AsyncResult<*> ->
        if (permissionGroup == null) {
            databaseManager.closeConnection(sqlConnection) {
                handler.invoke(Error(ErrorCode.UNKNOWN))
            }

            return@handler
        }

        if (permissionGroup.name == "admin") {
            databaseManager.closeConnection(sqlConnection) {
                handler.invoke(Error(ErrorCode.CANT_UPDATE_ADMIN_PERMISSION))
            }

            return@handler
        }

        databaseManager.permissionGroupPermsDao.doesPermissionGroupHavePermission(
            permissionGroupID,
            permissionID,
            sqlConnection,
            (this::doesPermissionGroupHavePermissionHandler)(
                sqlConnection,
                handler,
                permissionGroupID,
                permissionID,
                mode
            )
        )
    }

    private fun doesPermissionGroupHavePermissionHandler(
        sqlConnection: SqlConnection,
        handler: (result: Result) -> Unit,
        permissionGroupID: Int,
        permissionID: Int,
        mode: String
    ) = handler@{ isTherePermission: Boolean?, _: AsyncResult<*> ->
        if (isTherePermission == null) {
            databaseManager.closeConnection(sqlConnection) {
                handler.invoke(Error(ErrorCode.UNKNOWN))
            }

            return@handler
        }

        if (mode == "ADD")
            if (isTherePermission)
                databaseManager.closeConnection(sqlConnection) {
                    handler.invoke(Successful())
                }
            else
                databaseManager.permissionGroupPermsDao.addPermission(
                    permissionGroupID,
                    permissionID,
                    sqlConnection,
                    (this::resultHandler)(sqlConnection, handler)
                )
        else
            if (!isTherePermission)
                databaseManager.closeConnection(sqlConnection) {
                    handler.invoke(Successful())
                }
            else
                databaseManager.permissionGroupPermsDao.removePermission(
                    permissionGroupID,
                    permissionID,
                    sqlConnection,
                    (this::resultHandler)(sqlConnection, handler)
                )
    }

    private fun resultHandler(
        sqlConnection: SqlConnection,
        handler: (result: Result) -> Unit
    ) = handler@{ result: Result?, _: AsyncResult<*> ->
        databaseManager.closeConnection(sqlConnection) {
            if (result == null) {
                handler.invoke(Error(ErrorCode.UNKNOWN))

                return@closeConnection
            }

            handler.invoke(Successful())
        }
    }
}