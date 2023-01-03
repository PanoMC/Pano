package com.panomc.platform.route.api.panel.ticket

import com.panomc.platform.ErrorCode
import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.TicketMessage
import com.panomc.platform.model.*
import com.panomc.platform.notification.NotificationManager
import com.panomc.platform.notification.Notifications
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Bodies.json
import io.vertx.ext.web.validation.builder.Parameters.param
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaParser
import io.vertx.json.schema.common.dsl.Schemas.*

@Endpoint
class PanelSendTicketMessageAPI(
    private val authProvider: AuthProvider,
    private val databaseManager: DatabaseManager,
    private val notificationManager: NotificationManager
) : PanelApi() {
    override val paths = listOf(Path("/api/panel/tickets/:id/message", RouteType.POST))

    override fun getValidationHandler(schemaParser: SchemaParser): ValidationHandler =
        ValidationHandlerBuilder.create(schemaParser)
            .pathParameter(param("id", numberSchema()))
            .body(
                json(
                    objectSchema()
                        .property("message", stringSchema())
                )
            )
            .build()

    override suspend fun handler(context: RoutingContext): Result {
        val parameters = getParameters(context)
        val data = parameters.body().jsonObject

        val ticketId = parameters.pathParameter("id").long
        val message = data.getString("message")

        val userId = authProvider.getUserIdFromRoutingContext(context)

        val sqlConnection = createConnection(context)

        val ticket = databaseManager.ticketDao.getById(ticketId, sqlConnection) ?: throw Error(ErrorCode.NOT_EXISTS)

        val username = databaseManager.userDao.getUsernameFromUserId(userId, sqlConnection)

        val ticketMessage = TicketMessage(userId = userId, ticketId = ticketId, message = message, panel = 1)

        val messageId = databaseManager.ticketMessageDao.addMessage(ticketMessage, sqlConnection)

        databaseManager.ticketDao.makeStatus(ticketId, 2, sqlConnection)

        val notificationProperties = JsonObject()
            .put("id", ticketId)
            .put("whoReplied", username)

        notificationManager.sendNotification(
            ticket.userId,
            Notifications.UserNotificationType.AN_ADMIN_REPLIED_TICKET,
            notificationProperties,
            sqlConnection
        )

        databaseManager.ticketDao.updateLastUpdateDate(
            ticketMessage.ticketId,
            System.currentTimeMillis(),
            sqlConnection
        )

        return Successful(
            mapOf(
                "message" to mapOf(
                    "id" to messageId,
                    "userID" to ticketMessage.userId,
                    "ticketID" to ticketMessage.ticketId,
                    "username" to username,
                    "message" to ticketMessage.message,
                    "date" to ticketMessage.date,
                    "panel" to ticketMessage.panel
                )
            )
        )
    }
}