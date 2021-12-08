package com.panomc.platform.route.api.panel.player.permission

import com.panomc.platform.ErrorCode
import com.panomc.platform.db.model.PermissionGroup
import com.panomc.platform.model.*
import io.vertx.core.AsyncResult
import io.vertx.ext.web.RoutingContext
import io.vertx.sqlclient.SqlConnection
import kotlin.math.ceil

class PlayersByPermissionAPI : PanelApi() {
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
                "players_count" to count,
                "total_page" to totalPage,
                "permissionGroup" to permissionGroup
            )

            val handlers: List<(handler: () -> Unit) -> Any> =
                userList.map { user ->
                    val localHandler: (handler: () -> Unit) -> Any = { localHandler ->
                        databaseManager.getDatabase().ticketDao.countByUserID(
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

                databaseManager.getDatabase().userDao.getAllByPageAndPermissionGroup(
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

                databaseManager.getDatabase().userDao.getCountOfUsersByPermissionGroupID(
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

                databaseManager.getDatabase().permissionGroupDao.getPermissionGroupID(
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
                    databaseManager.getDatabase().userDao.getCountOfUsersByPermissionGroupID(
                        permissionGroupObject.id,
                        sqlConnection,
                        getCountOfUsersByPermissionGroupIDHandler(sqlConnection, permissionGroupObject)
                    )

                    return@createConnectionHandler
                }


                databaseManager.getDatabase().permissionGroupDao.isThere(
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

        if (user["permission_group_id"] as Int == -1) {
            addToPlayerList(user, playerList, count, null)

            localHandler.invoke()

            return@handler
        }

        databaseManager.getDatabase().permissionGroupDao.getPermissionGroupByID(
            user["permission_group_id"] as Int,
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
                "permission_group_id" to user["permission_group_id"] as Int,
                "permission_group" to (permissionGroup?.name ?: "-"),
                "ticket_count" to ticketCount,
                "register_date" to user["register_date"] as Long
            )
        )
    }
}