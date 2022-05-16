package com.panomc.platform.route.api.panel.ticket

import com.panomc.platform.ErrorCode
import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.Ticket
import com.panomc.platform.db.model.TicketCategory
import com.panomc.platform.db.model.TicketMessage
import com.panomc.platform.model.*
import com.panomc.platform.util.AuthProvider
import com.panomc.platform.util.SetupManager
import io.vertx.ext.web.RoutingContext

@Endpoint
class TicketDetailAPI(
    private val databaseManager: DatabaseManager,
    setupManager: SetupManager,
    authProvider: AuthProvider
) : PanelApi(setupManager, authProvider) {
    override val routeType = RouteType.POST

    override val routes = arrayListOf("/api/panel/initPage/ticket/detail")

    override suspend fun handler(context: RoutingContext): Result {
        val data = context.bodyAsJson
        val id = data.getInteger("id")

        val sqlConnection = createConnection(databaseManager, context)

        val exists = databaseManager.ticketDao.isExistsByID(id, sqlConnection)

        if (!exists) {
            throw Error(ErrorCode.NOT_EXISTS)
        }

        val ticket = databaseManager.ticketDao.getByID(id, sqlConnection) ?: throw Error(ErrorCode.UNKNOWN)

        val username = databaseManager.userDao.getUsernameFromUserID(ticket.userID, sqlConnection) ?: throw Error(
            ErrorCode.UNKNOWN
        )

        val messages = databaseManager.ticketMessageDao.getByTicketIDAndPage(id, sqlConnection)

        val userIDList = mutableListOf<Int>()

        messages.forEach { message ->
            if (userIDList.indexOf(message.userID) == -1)
                userIDList.add(message.userID)
        }

        val usernameList = databaseManager.userDao.getUsernameByListOfID(userIDList, sqlConnection)

        val count = databaseManager.ticketMessageDao.getCountByTicketID(ticket.id, sqlConnection)

        if (ticket.categoryID == -1) {
            return getResult(ticket, usernameList, null, username, messages, count)
        }

        val ticketCategory = databaseManager.ticketCategoryDao.getByID(ticket.categoryID, sqlConnection)

        return getResult(ticket, usernameList, ticketCategory, username, messages, count)
    }

    private fun getResult(
        ticket: Ticket,
        usernameList: Map<Int, String>,
        ticketCategory: TicketCategory?,
        username: String,
        ticketMessages: List<TicketMessage>,
        messageCount: Int
    ): Result {
        val messages = mutableListOf<Map<String, Any?>>()

        ticketMessages.forEach { ticketMessage ->
            messages.add(
                0,
                mapOf(
                    "id" to ticketMessage.id,
                    "userID" to ticketMessage.userID,
                    "ticketID" to ticketMessage.ticketID,
                    "username" to usernameList[ticketMessage.userID],
                    "message" to ticketMessage.message,
                    "date" to ticketMessage.date,
                    "panel" to ticketMessage.panel
                )
            )
        }

        return Successful(
            mapOf(
                "ticket" to mapOf(
                    "username" to username,
                    "title" to ticket.title,
                    "category" to
                            if (ticketCategory == null)
                                "-"
                            else
                                mapOf(
                                    "title" to ticketCategory.title
                                    ),
                        "messages" to messages,
                        "status" to ticket.status,
                        "date" to ticket.date,
                        "count" to messageCount
                    )
                )
            )
    }
}