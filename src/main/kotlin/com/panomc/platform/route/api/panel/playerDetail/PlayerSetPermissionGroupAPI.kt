package com.panomc.platform.route.api.panel.playerDetail

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
class PlayerSetPermissionGroupAPI(
    private val databaseManager: DatabaseManager,
    setupManager: SetupManager,
    authProvider: AuthProvider
) : PanelApi(setupManager, authProvider) {
    override val routeType = RouteType.POST

    override val routes = arrayListOf("/api/panel/player/set/permissionGroup")

    override fun handler(context: RoutingContext, handler: (result: Result) -> Unit) {
        val data = context.bodyAsJson

        val username = data.getString("username")
        val permissionGroup = data.getString("permissionGroup")

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
            databaseManager.userDao.isExistsByUsername(
                username,
                sqlConnection,
                (this::isExistsByUsernameHandler)(sqlConnection, handler, username, 0)
            )

            return@handler
        }

        databaseManager.permissionGroupDao.isThere(
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

        databaseManager.userDao.getPermissionGroupIDFromUsername(
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

        if (userPermissionGroupID == -1) {
            databaseManager.userDao.setPermissionGroupByUsername(
                permissionGroupID,
                username,
                sqlConnection,
                (this::setPermissionGroupByUsernameHandler)(sqlConnection, handler)
            )

            return@handler
        }

        databaseManager.permissionGroupDao.getPermissionGroupByID(
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
            databaseManager.userDao.getCountOfUsersByPermissionGroupID(
                permissionGroup.id,
                sqlConnection,
                (this::getCountOfUsersByPermissionGroupIDHandler)(sqlConnection, handler, username, permissionGroupID)
            )

            return@handler
        }

        databaseManager.userDao.setPermissionGroupByUsername(
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

        databaseManager.userDao.setPermissionGroupByUsername(
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

        databaseManager.permissionGroupDao.getPermissionGroupID(
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

        databaseManager.userDao.isExistsByUsername(
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