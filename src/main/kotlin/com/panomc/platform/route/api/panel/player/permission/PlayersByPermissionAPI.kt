package com.panomc.platform.route.api.panel.player.permission

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
import kotlin.math.ceil

@Endpoint
class PlayersByPermissionAPI(
    private val databaseManager: DatabaseManager,
    setupManager: SetupManager,
    authProvider: AuthProvider
) : PanelApi(setupManager, authProvider) {
    override val routeType = RouteType.POST

    override val routes = arrayListOf("/api/panel/players/byPermission")

    override fun handler(context: RoutingContext, handler: (result: Result) -> Unit) {
        val data = context.bodyAsJson
        val permissionGroup = data.getString("permissionGroup")
        val page = data.getInteger("page")

        fun getAllByPageAndPermissionGroupHandler(
            sqlConnection: SqlConnection,
            permissionGroup: PermissionGroup,
            count: Int,
            totalPage: Int
        ) = getAllByPageAndPermissionGroupHandler@{ userList: List<Map<String, Any>>?, _: AsyncResult<*> ->
            if (userList == null) {
                databaseManager.closeConnection(sqlConnection) {
                    handler.invoke(Error(ErrorCode.UNKNOWN))
                }

                return@getAllByPageAndPermissionGroupHandler
            }

            val playerList = mutableListOf<Map<String, Any>>()

            val result = mutableMapOf(
                "players" to playerList,
                "playerCount" to count,
                "totalPage" to totalPage,
                "permissionGroup" to permissionGroup
            )

            val handlers: List<(handler: () -> Unit) -> Any> =
                userList.map { user ->
                    val localHandler: (handler: () -> Unit) -> Any = { localHandler ->
                        databaseManager.ticketDao.countByUserID(
                            user["id"] as Int,
                            sqlConnection,
                            (this::countByUserIDHandler)(handler, sqlConnection, user, playerList, localHandler)
                        )
                    }

                    localHandler
                }

            var currentIndex = -1

            fun invoke() {
                val localHandler: () -> Unit = {
                    if (currentIndex == handlers.lastIndex)
                        databaseManager.closeConnection(sqlConnection) {
                            handler.invoke(Successful(result))
                        }
                    else
                        invoke()
                }

                currentIndex++

                if (currentIndex <= handlers.lastIndex)
                    handlers[currentIndex].invoke(localHandler)
            }

            if (userList.isEmpty()) {
                databaseManager.closeConnection(sqlConnection) {
                    handler.invoke(Successful(result))
                }

                return@getAllByPageAndPermissionGroupHandler
            }

            invoke()
        }

        fun getCountOfUsersByPermissionGroupIDHandler(sqlConnection: SqlConnection, permissionGroup: PermissionGroup) =
            getCountOfUsersByPermissionGroupIDHandler@{ count: Int?, _: AsyncResult<*> ->
                if (count == null) {
                    databaseManager.closeConnection(sqlConnection) {
                        handler.invoke(Error(ErrorCode.UNKNOWN))
                    }

                    return@getCountOfUsersByPermissionGroupIDHandler
                }

                if (count == 0) {

                    return@getCountOfUsersByPermissionGroupIDHandler
                }

                var totalPage = ceil(count.toDouble() / 10).toInt()

                if (totalPage < 1)
                    totalPage = 1

                if (page > totalPage || page < 1) {
                    databaseManager.closeConnection(sqlConnection) {
                        handler.invoke(Error(ErrorCode.PAGE_NOT_FOUND))
                    }

                    return@getCountOfUsersByPermissionGroupIDHandler
                }

                databaseManager.userDao.getAllByPageAndPermissionGroup(
                    page,
                    permissionGroup.id,
                    sqlConnection,
                    getAllByPageAndPermissionGroupHandler(sqlConnection, permissionGroup, count, totalPage)
                )
            }

        fun getPermissionGroupIDHandler(sqlConnection: SqlConnection) =
            getPermissionGroupIDHandler@{ permissionGroupID: Int?, _: AsyncResult<*> ->
                if (permissionGroupID == null) {
                    databaseManager.closeConnection(sqlConnection) {
                        handler.invoke(Error(ErrorCode.UNKNOWN))
                    }

                    return@getPermissionGroupIDHandler
                }

                val permissionGroupObject = PermissionGroup(permissionGroupID, permissionGroup)

                databaseManager.userDao.getCountOfUsersByPermissionGroupID(
                    permissionGroupID,
                    sqlConnection,
                    getCountOfUsersByPermissionGroupIDHandler(sqlConnection, permissionGroupObject)
                )
            }

        fun isTherePermissionHandler(sqlConnection: SqlConnection, permissionGroup: PermissionGroup) =
            isTherePermissionHandler@{ isTherePermission: Boolean?, _: AsyncResult<*> ->
                if (isTherePermission == null) {
                    databaseManager.closeConnection(sqlConnection) {
                        handler.invoke(Error(ErrorCode.UNKNOWN))
                    }

                    return@isTherePermissionHandler
                }

                if (!isTherePermission) {
                    databaseManager.closeConnection(sqlConnection) {
                        handler.invoke(Error(ErrorCode.NOT_EXISTS))
                    }

                    return@isTherePermissionHandler
                }

                databaseManager.permissionGroupDao.getPermissionGroupID(
                    permissionGroup,
                    sqlConnection,
                    getPermissionGroupIDHandler(sqlConnection)
                )
            }

        val createConnectionHandler =
            createConnectionHandler@{ sqlConnection: SqlConnection?, _: AsyncResult<SqlConnection> ->
                if (sqlConnection == null) {
                    handler.invoke(Error(ErrorCode.CANT_CONNECT_DATABASE))

                    return@createConnectionHandler
                }

                val permissionGroupObject = PermissionGroup(-1, permissionGroup)

                if (permissionGroup == "-") {
                    databaseManager.userDao.getCountOfUsersByPermissionGroupID(
                        permissionGroupObject.id,
                        sqlConnection,
                        getCountOfUsersByPermissionGroupIDHandler(sqlConnection, permissionGroupObject)
                    )

                    return@createConnectionHandler
                }


                databaseManager.permissionGroupDao.isThere(
                    permissionGroupObject,
                    sqlConnection,
                    isTherePermissionHandler(sqlConnection, permissionGroupObject)
                )
            }

        databaseManager.createConnection(createConnectionHandler)
    }

    private fun countByUserIDHandler(
        handler: (result: Result) -> Unit,
        sqlConnection: SqlConnection,
        user: Map<String, Any?>,
        playerList: MutableList<Map<String, Any>>,
        localHandler: () -> Unit
    ) = handler@{ count: Int?, _: AsyncResult<*> ->
        if (count == null) {
            databaseManager.closeConnection(sqlConnection) {
                handler.invoke(Error(ErrorCode.UNKNOWN))
            }

            return@handler
        }

        if (user["permissionGroupId"] as Int == -1) {
            addToPlayerList(user, playerList, count, null)

            localHandler.invoke()

            return@handler
        }

        databaseManager.permissionGroupDao.getPermissionGroupByID(
            user["permissionGroupId"] as Int,
            sqlConnection,
            (this::getPermissionGroupByIDHandler)(handler, sqlConnection, user, playerList, count, localHandler)
        )
    }

    private fun getPermissionGroupByIDHandler(
        handler: (result: Result) -> Unit,
        sqlConnection: SqlConnection,
        user: Map<String, Any?>,
        playerList: MutableList<Map<String, Any>>,
        ticketCount: Int,
        localHandler: () -> Unit
    ) = handler@{ permissionGroup: PermissionGroup?, _: AsyncResult<*> ->
        if (permissionGroup == null) {
            databaseManager.closeConnection(sqlConnection) {
                handler.invoke(Error(ErrorCode.UNKNOWN))
            }

            return@handler
        }

        addToPlayerList(user, playerList, ticketCount, permissionGroup)

        localHandler.invoke()
    }

    private fun addToPlayerList(
        user: Map<String, Any?>,
        playerList: MutableList<Map<String, Any>>,
        ticketCount: Int,
        permissionGroup: PermissionGroup?
    ) {
        playerList.add(
            mapOf(
                "id" to user["id"] as Int,
                "username" to user["username"] as String,
                "email" to user["email"] as String,
                "permissionGroupId" to user["permissionGroupId"] as Int,
                "permissionGroup" to (permissionGroup?.name ?: "-"),
                "ticketCount" to ticketCount,
                "registerDate" to user["registerDate"] as Long
            )
        )
    }
}