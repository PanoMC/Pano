package com.panomc.platform.route.api.ticket

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.PanelPermission
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.Ticket
import com.panomc.platform.db.model.TicketMessage
import com.panomc.platform.error.CategoryNotExists
import com.panomc.platform.error.MessageCantBeEmpty
import com.panomc.platform.error.TitleCantBeEmpty
import com.panomc.platform.model.*
import com.panomc.platform.notification.NotificationManager
import com.panomc.platform.notification.Notifications
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.RequestPredicate
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
            .predicate(RequestPredicate.BODY_REQUIRED)
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        val parameters = getParameters(context)
        val data = parameters.body().jsonObject

        val title = data.getString("title")
        val message = data.getString("message")
        val categoryId = data.getLong("categoryId")

        validateInput(title, message)

        val userId = authProvider.getUserIdFromRoutingContext(context)
        val sqlClient = getSqlClient()

        if (categoryId != -1L) {
            val isCategoryExists = databaseManager.ticketCategoryDao.existsById(categoryId, sqlClient)

            if (!isCategoryExists) {
                throw CategoryNotExists()
            }
        }

        val id = databaseManager.ticketDao.add(
            Ticket(
                title = title,
                categoryId = categoryId,
                userId = userId
            ), sqlClient
        )

        databaseManager.ticketMessageDao.addMessage(
            TicketMessage(userId = userId, ticketId = id, message = message),
            sqlClient
        )

        val notificationProperties = JsonObject().put("id", id)

        notificationManager.sendNotificationToAllWithPermission(
            Notifications.PanelNotificationType.NEW_TICKET,
            notificationProperties,
            PanelPermission.MANAGE_TICKETS,
            sqlClient
        )

        return Successful(
            mapOf(
                "id" to id
            )
        )
    }

    private fun validateInput(title: String, message: String) {
        if (title.isBlank()) {
            throw TitleCantBeEmpty()
        }

        if (message.isBlank()) {
            throw MessageCantBeEmpty()
        }
    }
}