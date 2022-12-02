package com.panomc.platform.route.api.panel.ticket

import com.panomc.platform.ErrorCode
import com.panomc.platform.Notifications
import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.Notification
import com.panomc.platform.model.*
import com.panomc.platform.util.AuthProvider
import com.panomc.platform.util.SetupManager
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Bodies.json
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaParser
import io.vertx.json.schema.common.dsl.Schemas.*

@Endpoint
class PanelUpdateTicketsAPI(
    private val databaseManager: DatabaseManager,
    setupManager: SetupManager,
    private val authProvider: AuthProvider
) : PanelApi(setupManager, authProvider) {
    override val paths = listOf(Path("/api/panel/tickets", RouteType.PUT))

    override fun getValidationHandler(schemaParser: SchemaParser): ValidationHandler =
        ValidationHandlerBuilder.create(schemaParser)
            .body(
                json(
                    objectSchema()
                        .property("tickets", arraySchema().items(numberSchema()))
                        .optionalProperty("status", stringSchema())
                )
            )
            .build()

    override suspend fun handler(context: RoutingContext): Result {
        val parameters = getParameters(context)
        val data = parameters.body().jsonObject
        val selectedTickets = data.getJsonArray("tickets")
        val ticketStatus = data.getString("status")

        val userId = authProvider.getUserIdFromRoutingContext(context)

        if (selectedTickets.isEmpty) {
            return Successful()
        }

        if (ticketStatus != null && ticketStatus == "close") {
            val sqlConnection = createConnection(databaseManager, context)

            val areIdListExist =
                databaseManager.ticketDao.areIdListExist(selectedTickets.map { it.toString().toLong() }, sqlConnection)

            if (!areIdListExist) {
                throw Error(ErrorCode.SOME_TICKETS_ARENT_EXIST)
            }

            databaseManager.ticketDao.closeTickets(selectedTickets, sqlConnection)

            val username = databaseManager.userDao.getUsernameFromUserId(userId, sqlConnection)

            selectedTickets.map { it.toString().toLong() }.forEach {
                val ticket = databaseManager.ticketDao.getById(it, sqlConnection)!!

                databaseManager.notificationDao.add(
                    Notification(
                        userId = ticket.userId,
                        typeId = Notifications.UserNotification.AN_ADMIN_CLOSED_TICKET.typeId,
                        action = Notifications.UserNotification.AN_ADMIN_CLOSED_TICKET.action,
                        properties = JsonObject().put("id", it).put("whoClosed", username)
                    ),
                    sqlConnection
                )
            }
        }

        return Successful()
    }
}