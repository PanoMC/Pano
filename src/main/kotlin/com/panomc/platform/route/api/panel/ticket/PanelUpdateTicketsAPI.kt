package com.panomc.platform.route.api.panel.ticket

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.PanelPermission
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.error.SomeTicketsArentExists
import com.panomc.platform.model.*
import com.panomc.platform.notification.NotificationManager
import com.panomc.platform.notification.Notifications
import com.panomc.platform.util.TicketStatus
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.RequestPredicate
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Bodies.json
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaParser
import io.vertx.json.schema.common.dsl.Schemas.*

@Endpoint
class PanelUpdateTicketsAPI(
    private val databaseManager: DatabaseManager,
    private val authProvider: AuthProvider,
    private val notificationManager: NotificationManager
) : PanelApi() {
    override val paths = listOf(Path("/api/panel/tickets", RouteType.PUT))

    override fun getValidationHandler(schemaParser: SchemaParser): ValidationHandler =
        ValidationHandlerBuilder.create(schemaParser)
            .body(
                json(
                    objectSchema()
                        .property("tickets", arraySchema().items(numberSchema()))
                        .optionalProperty("status", enumSchema(*TicketStatus.entries.map { it.name }.toTypedArray()))
                )
            )
            .predicate(RequestPredicate.BODY_REQUIRED)
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        authProvider.requirePermission(PanelPermission.MANAGE_TICKETS, context)

        val parameters = getParameters(context)
        val data = parameters.body().jsonObject
        val selectedTickets = data.getJsonArray("tickets")
        val ticketStatus =
            if (data.getString("status") == null) null else TicketStatus.valueOf(data.getString("status"))

        val userId = authProvider.getUserIdFromRoutingContext(context)

        if (selectedTickets.isEmpty) {
            return Successful()
        }

        if (ticketStatus != null && ticketStatus == TicketStatus.CLOSED) {
            val sqlClient = getSqlClient()

            val areIdListExist =
                databaseManager.ticketDao.areIdListExist(selectedTickets.map { it.toString().toLong() }, sqlClient)

            if (!areIdListExist) {
                throw SomeTicketsArentExists()
            }

            databaseManager.ticketDao.closeTickets(selectedTickets, sqlClient)

            val username = databaseManager.userDao.getUsernameFromUserId(userId, sqlClient)

            selectedTickets.map { it.toString().toLong() }.forEach {
                val ticket = databaseManager.ticketDao.getById(it, sqlClient)!!

                val notificationProperties = JsonObject()
                    .put("id", it)
                    .put("whoClosed", username)

                notificationManager.sendNotification(
                    ticket.userId,
                    Notifications.UserNotificationType.AN_ADMIN_CLOSED_TICKET,
                    notificationProperties,
                    sqlClient
                )
            }
        }

        return Successful()
    }
}