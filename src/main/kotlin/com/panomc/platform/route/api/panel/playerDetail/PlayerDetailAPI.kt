package com.panomc.platform.route.api.panel.playerDetail

import com.panomc.platform.ErrorCode
import com.panomc.platform.db.model.PermissionGroup
import com.panomc.platform.db.model.Ticket
import com.panomc.platform.db.model.TicketCategory
import com.panomc.platform.db.model.User
import com.panomc.platform.model.*
import io.vertx.core.AsyncResult
import io.vertx.ext.web.RoutingContext
import io.vertx.sqlclient.SqlConnection
import kotlin.math.ceil

class PlayerDetailAPI : PanelApi() {
    override val routeType = RouteType.POST

    override val routes = arrayListOf("/api/panel/initPage/playerDetail")

    override fun handler(context: RoutingContext, handler: (result: Result) -> Unit) {
        val data = context.bodyAsJson
        val username = data.getString("username")
        val page = data.getInteger("page")

        databaseManager.createConnection((this::createConnectionHandler)(handler, username, page))
    }

    private fun createConnectionHandler(
        handler: (result: Result) -> Unit,
        username: String,
        page: Int
    ) = handler@{ sqlConnection: SqlConnection?, _: AsyncResult<SqlConnection> ->
        if (sqlConnection == null) {
            handler.invoke(Error(ErrorCode.CANT_CONNECT_DATABASE))

            return@handler
        }

        databaseManager.getDatabase().userDao.isExistsByUsername(
            username,
            sqlConnection,
            (this::isExistsByHandler)(handler, username, sqlConnection, page)
        )
    }

    private fun isExistsByHandler(
        handler: (result: Result) -> Unit,
        username: String,
        sqlConnection: SqlConnection,
        page: Int
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

        databaseManager.getDatabase().userDao.getByUsername(
            username,
            sqlConnection,
            (this::getByUsernameHandler)(handler, sqlConnection, page)
        )
    }

    private fun getByUsernameHandler(
        handler: (result: Result) -> Unit,
        sqlConnection: SqlConnection,
        page: Int
    ) = handler@{ user: User?, _: AsyncResult<*> ->
        if (user == null) {
            databaseManager.closeConnection(sqlConnection) {
                handler.invoke(Error(ErrorCode.UNKNOWN))
            }

            return@handler
        }

        val result = mutableMapOf<String, Any?>()

        result["player"] = mutableMapOf<String, Any?>(
            "id" to user.id,
            "username" to user.username,
            "email" to user.email,
            "registerDate" to user.registerDate,
            "isBanned" to user.banned
        )

        if (user.permissionGroupID == -1) {
            @Suppress("UNCHECKED_CAST")
            (result["player"] as MutableMap<String, Any?>)["permission_group"] = "-"

            databaseManager.getDatabase().ticketDao.countByUserID(
                user.id,
                sqlConnection,
                (this::countByUserIDHandler)(handler, sqlConnection, result, user, page)
            )

            return@handler
        }

        databaseManager.getDatabase().permissionGroupDao.getPermissionGroupByID(
            user.permissionGroupID,
            sqlConnection,
            (this::getPermissionByIDHandler)(handler, sqlConnection, result, user, page)
        )
    }

    private fun getPermissionByIDHandler(
        handler: (result: Result) -> Unit,
        sqlConnection: SqlConnection,
        result: MutableMap<String, Any?>,
        user: User,
        page: Int
    ) = handler@{ permissionGroup: PermissionGroup?, _: AsyncResult<*> ->
        if (permissionGroup == null) {
            databaseManager.closeConnection(sqlConnection) {
                handler.invoke(Error(ErrorCode.UNKNOWN))
            }

            return@handler
        }

        @Suppress("UNCHECKED_CAST")
        (result["player"] as MutableMap<String, Any?>)["permission_group"] = permissionGroup.name

        databaseManager.getDatabase().ticketDao.countByUserID(
            user.id,
            sqlConnection,
            (this::countByUserIDHandler)(handler, sqlConnection, result, user, page)
        )
    }

    private fun countByUserIDHandler(
        handler: (result: Result) -> Unit,
        sqlConnection: SqlConnection,
        result: MutableMap<String, Any?>,
        user: User,
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

        result["ticketCount"] = count
        result["ticketTotalPage"] = totalPage

        if (count == 0) {
            prepareTickets(
                handler,
                sqlConnection,
                result,
                listOf(),
                mapOf(),
                user.username
            )

            return@handler
        }

        databaseManager.getDatabase().ticketDao.getAllByUserIDAndPage(
            user.id,
            page,
            sqlConnection,
            (this::getAllByUserIDAndPageHandler)(handler, sqlConnection, result, user.username)
        )
    }

    private fun getAllByUserIDAndPageHandler(
        handler: (result: Result) -> Unit,
        sqlConnection: SqlConnection,
        result: MutableMap<String, Any?>,
        username: String
    ) = handler@{ tickets: List<Ticket>?, _: AsyncResult<*> ->
        if (tickets == null) {
            databaseManager.closeConnection(sqlConnection) {
                handler.invoke(Error(ErrorCode.UNKNOWN))
            }

            return@handler
        }

        val categoryIDList = tickets.filter { it.categoryID != -1 }.distinctBy { it.categoryID }.map { it.categoryID }

        if (categoryIDList.isEmpty()) {
            prepareTickets(
                handler,
                sqlConnection,
                result,
                tickets,
                mapOf(),
                username
            )

            return@handler
        }

        databaseManager.getDatabase().ticketCategoryDao.getByIDList(
            categoryIDList,
            sqlConnection,
            (this::getByIDListHandler)(
                handler,
                sqlConnection,
                tickets,
                result,
                username
            )
        )
    }

    private fun getByIDListHandler(
        handler: (result: Result) -> Unit,
        sqlConnection: SqlConnection,
        tickets: List<Ticket>,
        result: MutableMap<String, Any?>,
        username: String
    ) = handler@{ ticketCategoryList: Map<Int, TicketCategory>?, _: AsyncResult<*> ->
        if (ticketCategoryList == null) {
            databaseManager.closeConnection(sqlConnection) {
                handler.invoke(Error(ErrorCode.UNKNOWN))
            }

            return@handler
        }

        prepareTickets(handler, sqlConnection, result, tickets, ticketCategoryList, username)
    }

    private fun prepareTickets(
        handler: (result: Result) -> Unit,
        sqlConnection: SqlConnection,
        result: MutableMap<String, Any?>,
        tickets: List<Ticket>,
        ticketCategoryList: Map<Int, TicketCategory>,
        username: String
    ) {
        databaseManager.closeConnection(sqlConnection) {
            val ticketDataList = mutableListOf<Map<String, Any?>>()

            tickets.forEach { ticket ->
                ticketDataList.add(
                    mapOf(
                        "id" to ticket.id,
                        "title" to ticket.title,
                        "category" to
                                if (ticket.categoryID == -1)
                                    mapOf("id" to -1, "title" to "-")
                                else
                                    ticketCategoryList.getOrDefault(
                                        ticket.categoryID,
                                        mapOf("id" to -1, "title" to "-")
                                    ),
                        "writer" to mapOf(
                            "username" to username
                        ),
                        "date" to ticket.date,
                        "last_update" to ticket.lastUpdate,
                        "status" to ticket.status
                    )
                )
            }

            result["tickets"] = ticketDataList

            handler.invoke(Successful(result))
        }
    }
}