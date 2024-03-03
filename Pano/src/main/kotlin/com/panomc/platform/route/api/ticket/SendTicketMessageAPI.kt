package com.panomc.platform.route.api.ticket


import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.PanelPermission
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.TicketMessage
import com.panomc.platform.error.MessageCantBeEmpty
import com.panomc.platform.error.NoPermission
import com.panomc.platform.error.NotExists
import com.panomc.platform.error.TicketIsClosed
import com.panomc.platform.model.*
import com.panomc.platform.notification.NotificationManager
import com.panomc.platform.notification.Notifications
import com.panomc.platform.util.TicketStatus
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.RequestPredicate
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Bodies.json
import io.vertx.ext.web.validation.builder.Parameters.param
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaParser
import io.vertx.json.schema.common.dsl.Schemas.*

@Endpoint
class SendTicketMessageAPI(
    private val authProvider: AuthProvider,
    private val databaseManager: DatabaseManager,
    private val notificationManager: NotificationManager
) : LoggedInApi() {
    override val paths = listOf(Path("/api/tickets/:id/message", RouteType.POST))

    override fun getValidationHandler(schemaParser: SchemaParser): ValidationHandler =
        ValidationHandlerBuilder.create(schemaParser)
            .pathParameter(param("id", numberSchema()))
            .body(
                json(
                    objectSchema()
                        .property("message", stringSchema())
                )
            )
            .predicate(RequestPredicate.BODY_REQUIRED)
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        val parameters = getParameters(context)
        val data = parameters.body().jsonObject

        val ticketId = parameters.pathParameter("id").long
        val message = data.getString("message")

        if (message.isBlank()) {
            throw MessageCantBeEmpty()
        }

        val userId = authProvider.getUserIdFromRoutingContext(context)

        val sqlClient = getSqlClient()

        val exists = databaseManager.ticketDao.existsById(ticketId, sqlClient)

        if (!exists) {
            throw NotExists()
        }

        val isBelong = databaseManager.ticketDao.isIdBelongToUserId(ticketId, userId, sqlClient)

        if (!isBelong) {
            throw NoPermission()
        }

        val isTicketClosed = databaseManager.ticketDao.getStatusById(ticketId, sqlClient) == TicketStatus.CLOSED

        if (isTicketClosed) {
            throw TicketIsClosed()
        }

        val username = databaseManager.userDao.getUsernameFromUserId(userId, sqlClient)

        val ticketMessage = TicketMessage(userId = userId, ticketId = ticketId, message = message)

        val messageId = databaseManager.ticketMessageDao.addMessage(ticketMessage, sqlClient)

        databaseManager.ticketDao.updateLastUpdateDate(
            ticketMessage.ticketId,
            System.currentTimeMillis(),
            sqlClient
        )

        val notificationProperties = JsonObject().put("id", ticketId)

        notificationManager.sendNotificationToAllWithPermission(
            Notifications.PanelNotificationType.NEW_TICKET_MESSAGE,
            notificationProperties,
            PanelPermission.MANAGE_TICKETS,
            sqlClient
        )

        return Successful(
            mapOf(
                "message" to mapOf(
                    "id" to messageId,
                    "userId" to ticketMessage.userId,
                    "ticketId" to ticketMessage.ticketId,
                    "username" to username,
                    "message" to ticketMessage.message,
                    "date" to ticketMessage.date,
                    "panel" to ticketMessage.panel
                )
            )
        )
    }
}