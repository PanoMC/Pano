package com.panomc.platform.route.api.panel.playerDetail

import com.panomc.platform.ErrorCode
import com.panomc.platform.db.model.PermissionGroup
import com.panomc.platform.model.*
import io.vertx.core.AsyncResult
import io.vertx.ext.web.RoutingContext
import io.vertx.sqlclient.SqlConnection

class PlayerSetPermissionGroupAPI : PanelApi() {
    override val routeType = RouteType.POST

    override val routes = arrayListOf("/api/panel/player/set/permissionGroup")

    override fun handler(context: RoutingContext, handler: (result: Result) -> Unit) {
        val data = context.bodyAsJson

        val username = data.getString("username")
        val permissionGroup = data.getString("permission_group")

        databaseManager.createConnection(
            (this::createConnectionHandler)(
                handler,
                username,
                permissionGroup
            )
        )
    }

    private fun createConnectionHandler(
        handler: (result: Result) -> Unit,
        username: String,
        permissionGroup: String,
    ) = handler@{ sqlConnection: SqlConnection?, _: AsyncResult<SqlConnection> ->
        if (sqlConnection == null) {
            handler.invoke(Error(ErrorCode.CANT_CONNECT_DATABASE))

            return@handler
        }

        if (permissionGroup == "-") {
            databaseManager.getDatabase().userDao.isExistsByUsername(
                username,
                sqlConnection,
                (this::isExistsByUsernameHandler)(sqlConnection, handler, username, 0)
            )

            return@handler
        }

        databaseManager.getDatabase().permissionGroupDao.isThere(
            PermissionGroup(-1, permissionGroup),
            sqlConnection,
            (this::isThereHandler)(sqlConnection, handler, username, permissionGroup)
        )
    }

    private fun isExistsByUsernameHandler(
        sqlConnection: SqlConnection,
        handler: (result: Result) -> Unit,
        username: String,
        permissionGroupID: Int
    ) = handler@{ exists: Boolean?, _: AsyncResult<*> ->
        if (exists == null) {
            databaseManager.closeConnection(sqlConnection) {
                handler.invoke(Error(ErrorCode.UNKNOWN))
            }

            return@handler
        }

        if (!exists) {
            databaseManager.closeConnection(sqlConnection) {
                handler.invoke(Error(ErrorCode.NOT_EXISTS))
            }

            return@handler
        }

        databaseManager.getDatabase().userDao.getPermissionGroupIDFromUsername(
            username,
            sqlConnection,
            (this::getPermissionGroupIDFromUsernameHandler)(sqlConnection, handler, username, permissionGroupID)
        )
    }

    private fun getPermissionGroupIDFromUsernameHandler(
        sqlConnection: SqlConnection,
        handler: (result: Result) -> Unit,
        username: String,
        permissionGroupID: Int
    ) = handler@{ userPermissionGroupID: Int?, _: AsyncResult<*> ->
        if (userPermissionGroupID == null) {
            databaseManager.closeConnection(sqlConnection) {
                handler.invoke(Error(ErrorCode.UNKNOWN))
            }

            return@handler
        }

        databaseManager.getDatabase().permissionGroupDao.getPermissionGroupByID(
            userPermissionGroupID,
            sqlConnection,
            (this::getPermissionGroupByIDHandler)(sqlConnection, handler, username, permissionGroupID)
        )
    }

    private fun getPermissionGroupByIDHandler(
        sqlConnection: SqlConnection,
        handler: (result: Result) -> Unit,
        username: String,
        permissionGroupID: Int
    ) = handler@{ permissionGroup: PermissionGroup?, _: AsyncResult<*> ->
        if (permissionGroup == null) {
            databaseManager.closeConnection(sqlConnection) {
                handler.invoke(Error(ErrorCode.UNKNOWN))
            }

            return@handler
        }

        if (permissionGroup.name == "admin") {
            databaseManager.getDatabase().userDao.getCountOfUsersByPermissionGroupID(
                permissionGroup.id,
                sqlConnection,
                (this::getCountOfUsersByPermissionGroupIDHandler)(sqlConnection, handler, username, permissionGroupID)
            )

            return@handler
        }

        databaseManager.getDatabase().userDao.setPermissionGroupByUsername(
            permissionGroupID,
            username,
            sqlConnection,
            (this::setPermissionGroupByUsernameHandler)(sqlConnection, handler)
        )
    }

    private fun getCountOfUsersByPermissionGroupIDHandler(
        sqlConnection: SqlConnection,
        handler: (result: Result) -> Unit,
        username: String,
        permissionGroupID: Int
    ) = handler@{ count: Int?, _: AsyncResult<*> ->
        if (count == null) {
            databaseManager.closeConnection(sqlConnection) {
                handler.invoke(Error(ErrorCode.UNKNOWN))
            }

            return@handler
        }

        if (count == 1) {
            databaseManager.closeConnection(sqlConnection) {
                handler.invoke(Errors(mapOf("LAST_ADMIN" to true)))
            }

            return@handler
        }

        databaseManager.getDatabase().userDao.setPermissionGroupByUsername(
            permissionGroupID,
            username,
            sqlConnection,
            (this::setPermissionGroupByUsernameHandler)(sqlConnection, handler)
        )
    }

    private fun isThereHandler(
        sqlConnection: SqlConnection,
        handler: (result: Result) -> Unit,
        username: String,
        permissionGroup: String
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

        databaseManager.getDatabase().permissionGroupDao.getPermissionGroupID(
            PermissionGroup(-1, permissionGroup),
            sqlConnection,
            (this::getPermissionGroupIDHandler)(sqlConnection, handler, username)
        )
    }

    private fun getPermissionGroupIDHandler(
        sqlConnection: SqlConnection,
        handler: (result: Result) -> Unit,
        username: String,
    ) = handler@{ permissionGroupID: Int?, _: AsyncResult<*> ->
        if (permissionGroupID == null) {
            databaseManager.closeConnection(sqlConnection) {
                handler.invoke(Error(ErrorCode.UNKNOWN))
            }

            return@handler
        }

        databaseManager.getDatabase().userDao.isExistsByUsername(
            username,
            sqlConnection,
            (this::isExistsByUsernameHandler)(sqlConnection, handler, username, permissionGroupID)
        )
    }

    private fun setPermissionGroupByUsernameHandler(
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