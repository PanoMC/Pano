package com.panomc.platform.route.api.panel

import com.panomc.platform.ErrorCode
import com.panomc.platform.db.model.PermissionGroup
import com.panomc.platform.model.*
import io.vertx.core.AsyncResult
import io.vertx.ext.web.RoutingContext
import io.vertx.sqlclient.SqlConnection
import kotlin.math.ceil

class PlayersPageInitAPI : PanelApi() {
    override val routeType = RouteType.POST

    override val routes = arrayListOf("/api/panel/initPage/playersPage")

    override fun handler(context: RoutingContext, handler: (result: Result) -> Unit) {
        val data = context.bodyAsJson
        val pageType = data.getInteger("page_type")
        val page = data.getInteger("page")

        databaseManager.createConnection((this::createConnectionHandler)(handler, pageType, page))
    }

    private fun createConnectionHandler(
        handler: (result: Result) -> Unit,
        pageType: Int,
        page: Int
    ) = handler@{ sqlConnection: SqlConnection?, _: AsyncResult<SqlConnection> ->
        if (sqlConnection == null) {
            handler.invoke(Error(ErrorCode.CANT_CONNECT_DATABASE))

            return@handler
        }

        databaseManager.getDatabase().userDao.countByPageType(
            pageType,
            sqlConnection,
            (this::countByPageTypeHandler)(handler, sqlConnection, pageType, page)
        )
    }

    private fun countByPageTypeHandler(
        handler: (result: Result) -> Unit,
        sqlConnection: SqlConnection,
        pageType: Int,
        page: Int
    ) = handler@{ count: Int?, _: AsyncResult<*> ->
        if (count == null) {
            databaseManager.closeConnection(sqlConnection) {
                handler.invoke(Error(ErrorCode.UNKNOWN))
            }

            return@handler
        }

        var totalPage = ceil(count.toDouble() / 10).toInt()

        if (totalPage < 1)
            totalPage = 1

        if (page > totalPage || page < 1) {
            databaseManager.closeConnection(sqlConnection) {
                handler.invoke(Error(ErrorCode.PAGE_NOT_FOUND))
            }

            return@handler
        }

        databaseManager.getDatabase().userDao.getAllByPageAndPageType(
            page,
            pageType,
            sqlConnection,
            (this::getAllByPageAndPageTypeHandler)(handler, sqlConnection, count, totalPage)
        )
    }

    private fun getAllByPageAndPageTypeHandler(
        handler: (result: Result) -> Unit,
        sqlConnection: SqlConnection,
        count: Int,
        totalPage: Int
    ) = handler@{ userList: List<Map<String, Any>>?, _: AsyncResult<*> ->
        if (userList == null) {
            databaseManager.closeConnection(sqlConnection) {
                handler.invoke(Error(ErrorCode.UNKNOWN))
            }

            return@handler
        }

        val playerList = mutableListOf<Map<String, Any>>()

        val result = mutableMapOf(
            "players" to playerList,
            "players_count" to count,
            "total_page" to totalPage
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

            return@handler
        }

        invoke()
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