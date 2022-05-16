package com.panomc.platform.route.api.panel.playerDetail

import com.panomc.platform.ErrorCode
import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.Ticket
import com.panomc.platform.db.model.TicketCategory
import com.panomc.platform.model.*
import com.panomc.platform.util.AuthProvider
import com.panomc.platform.util.SetupManager
import io.vertx.ext.web.RoutingContext
import kotlin.math.ceil

@Endpoint
class PlayerDetailAPI(
    private val databaseManager: DatabaseManager,
    setupManager: SetupManager,
    authProvider: AuthProvider
) : PanelApi(setupManager, authProvider) {
    override val routeType = RouteType.POST

    override val routes = arrayListOf("/api/panel/initPage/playerDetail")

    override suspend fun handler(context: RoutingContext): Result {
        val data = context.bodyAsJson
        val username = data.getString("username")
        val page = data.getInteger("page")

        val sqlConnection = createConnection(databaseManager, context)

        val exists = databaseManager.userDao.isExistsByUsername(username, sqlConnection)

        if (!exists) {
            throw Error(ErrorCode.NOT_EXISTS)
        }

        val user = databaseManager.userDao.getByUsername(
            username,
            sqlConnection
        ) ?: throw Error(ErrorCode.UNKNOWN)

        val result = mutableMapOf<String, Any?>()

        result["player"] = mutableMapOf<String, Any?>(
            "id" to user.id,
            "username" to user.username,
            "email" to user.email,
            "registerDate" to user.registerDate,
            "isBanned" to user.banned,
            "isEmailVerified" to user.emailVerified
        )

        if (user.permissionGroupID != -1) {
            val permissionGroup = databaseManager.permissionGroupDao.getPermissionGroupByID(
                user.permissionGroupID,
                sqlConnection
            ) ?: throw Error(ErrorCode.UNKNOWN)

            @Suppress("UNCHECKED_CAST")
            (result["player"] as MutableMap<String, Any?>)["permissionGroup"] = permissionGroup.name
        }

        val count = databaseManager.ticketDao.countByUserID(user.id, sqlConnection)

        var totalPage = ceil(count.toDouble() / 10).toInt()

        if (totalPage < 1)
            totalPage = 1

        if (page > totalPage || page < 1) {
            throw Error(ErrorCode.PAGE_NOT_FOUND)
        }

        result["ticketCount"] = count
        result["ticketTotalPage"] = totalPage

        if (count == 0) {
            return getTickets(result, listOf(), mapOf(), user.username)
        }

        val tickets = databaseManager.ticketDao.getAllByUserIDAndPage(user.id, page, sqlConnection)

        val categoryIDList = tickets.filter { it.categoryID != -1 }.distinctBy { it.categoryID }.map { it.categoryID }

        if (categoryIDList.isEmpty()) {
            return getTickets(result, tickets, mapOf(), username)
        }

        val ticketCategoryList = databaseManager.ticketCategoryDao.getByIDList(categoryIDList, sqlConnection)

        return getTickets(result, tickets, ticketCategoryList, username)
    }

    private fun getTickets(
        result: MutableMap<String, Any?>,
        tickets: List<Ticket>,
        ticketCategoryList: Map<Int, TicketCategory>,
        username: String
    ): Result {
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
                    "lastUpdate" to ticket.lastUpdate,
                    "status" to ticket.status
                )
            )
        }

        result["tickets"] = ticketDataList

        return Successful(result)
    }
}