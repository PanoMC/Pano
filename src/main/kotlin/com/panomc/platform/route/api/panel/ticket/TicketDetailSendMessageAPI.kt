package com.panomc.platform.route.api.panel.ticket

import com.panomc.platform.ErrorCode
import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.TicketMessage
import com.panomc.platform.model.*
import com.panomc.platform.util.AuthProvider
import com.panomc.platform.util.SetupManager
import io.vertx.ext.web.RoutingContext

@Endpoint
class TicketDetailSendMessageAPI(
    private val authProvider: AuthProvider,
    private val databaseManager: DatabaseManager,
    setupManager: SetupManager
) : PanelApi(setupManager, authProvider) {
    override val routeType = RouteType.POST

    override val routes = arrayListOf("/api/panel/ticket/detail/message/send")

    override suspend fun handler(context: RoutingContext): Result {
        val data = context.bodyAsJson

        val ticketID = data.getInteger("ticketId")
        val message = data.getString("message")

        val userID = authProvider.getUserIDFromRoutingContext(context)

        val sqlConnection = createConnection(databaseManager, context)

        val exists = databaseManager.ticketDao.isExistsByID(ticketID, sqlConnection)

        if (!exists) {
            throw Error(ErrorCode.NOT_EXISTS)
        }

        val username = databaseManager.userDao.getUsernameFromUserID(userID, sqlConnection)

        val ticketMessage = TicketMessage(-1, userID, ticketID, message, System.currentTimeMillis(), 1)

        val messageId = databaseManager.ticketMessageDao.addMessage(ticketMessage, sqlConnection)

        databaseManager.ticketDao.makeStatus(ticketMessage.ticketID, 2, sqlConnection)

        databaseManager.ticketDao.updateLastUpdateDate(
            ticketMessage.ticketID,
            System.currentTimeMillis(),
            sqlConnection
        )

        return Successful(
            mapOf(
                "message" to mapOf(
                    "id" to messageId,
                    "userID" to ticketMessage.userID,
                    "ticketID" to ticketMessage.ticketID,
                    "username" to username,
                    "message" to ticketMessage.message,
                    "date" to ticketMessage.date,
                    "panel" to ticketMessage.panel
                )
            )
        )
    }
}