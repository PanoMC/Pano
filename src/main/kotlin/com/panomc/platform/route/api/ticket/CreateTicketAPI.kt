package com.panomc.platform.route.api.ticket

import com.panomc.platform.ErrorCode
import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.PanelPermission
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.Ticket
import com.panomc.platform.db.model.TicketMessage
import com.panomc.platform.model.*
import com.panomc.platform.notification.NotificationManager
import com.panomc.platform.notification.Notifications
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Bodies
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaParser
import io.vertx.json.schema.common.dsl.Schemas.*

@Endpoint
class CreateTicketAPI(
    private val databaseManager: DatabaseManager,
    private val authProvider: AuthProvider,
    private val notificationManager: NotificationManager
) : LoggedInApi() {
    override val paths = listOf(Path("/api/tickets", RouteType.POST))

    override fun getValidationHandler(schemaParser: SchemaParser): ValidationHandler =
        ValidationHandlerBuilder.create(schemaParser)
            .body(
                Bodies.json(
                    objectSchema()
                        .property("title", stringSchema())
                        .property("message", stringSchema())
                        .property("categoryId", numberSchema())
                )
            )
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        val parameters = getParameters(context)
        val data = parameters.body().jsonObject

        val title = data.getString("title")
        val message = data.getString("message")
        val categoryId = data.getLong("categoryId")

        validateInput(title, message)

        val userId = authProvider.getUserIdFromRoutingContext(context)
        val sqlConnection = createConnection(context)

        if (categoryId != -1L) {
            val isCategoryExists = databaseManager.ticketCategoryDao.existsById(categoryId, sqlConnection)

            if (!isCategoryExists) {
                throw Error(ErrorCode.CATEGORY_NOT_EXISTS)
            }
        }

        val id = databaseManager.ticketDao.save(
            Ticket(
                title = title,
                categoryId = categoryId,
                userId = userId
            ), sqlConnection
        )

        databaseManager.ticketMessageDao.addMessage(
            TicketMessage(userId = userId, ticketId = id, message = message),
            sqlConnection
        )

        val notificationProperties = JsonObject().put("id", id)

        notificationManager.sendNotificationToAllWithPermission(
            Notifications.PanelNotificationType.NEW_TICKET,
            notificationProperties,
            PanelPermission.MANAGE_TICKETS,
            sqlConnection
        )

        return Successful(
            mapOf(
                "id" to id
            )
        )
    }

    private fun validateInput(title: String, message: String) {
        if (title.isEmpty()) {
            throw Error(ErrorCode.TITLE_CANT_BE_EMPTY)
        }

        if (message.isEmpty()) {
            throw Error(ErrorCode.DESCRIPTION_CANT_BE_EMPTY)
        }
    }
}