package com.panomc.platform.route.api.panel.ticket

import com.panomc.platform.ErrorCode
import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.model.*
import com.panomc.platform.util.AuthProvider
import com.panomc.platform.util.SetupManager
import io.vertx.ext.web.RoutingContext

@Endpoint
class TicketDetailMessagePageAPI(
    private val databaseManager: DatabaseManager,
    setupManager: SetupManager,
    authProvider: AuthProvider
) : PanelApi(setupManager, authProvider) {
    override val routeType = RouteType.POST

    override val routes = arrayListOf("/api/panel/ticket/detail/message/page")

    override suspend fun handler(context: RoutingContext): Result {
        val data = context.bodyAsJson
        val id = data.getInteger("id")
        val lastMessageID = data.getInteger("lastMessageId")

        val sqlConnection = createConnection(databaseManager, context)

        val exists = databaseManager.ticketDao.isExistsByID(id, sqlConnection)

        if (!exists) {
            throw Error(ErrorCode.NOT_EXISTS)
        }

        val ticketMessages =
            databaseManager.ticketMessageDao.getByTicketIDPageAndStartFromID(lastMessageID, id, sqlConnection)

        val userIDList = mutableListOf<Int>()

        ticketMessages.forEach { message ->
            if (userIDList.indexOf(message.userID) == -1)
                userIDList.add(message.userID)
        }

        val usernameList = databaseManager.userDao.getUsernameByListOfID(userIDList, sqlConnection)

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
                "messages" to messages
            )
        )

    }
}