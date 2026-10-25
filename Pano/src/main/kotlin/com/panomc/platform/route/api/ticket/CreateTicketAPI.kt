package com.panomc.platform.route.api.ticket

import io.vertx.sqlclient.SqlClient

import com.panomc.platform.ticket.TicketTemplateRenderer

import com.panomc.platform.db.model.TicketCategory

import com.panomc.platform.config.ConfigManager

import com.panomc.platform.webhook.WebhookCoreEvents
import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.ticket.TicketSettings
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.panel.permission.ManageTicketsPermission
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.Ticket
import com.panomc.platform.db.model.TicketMessage
import com.panomc.platform.error.CategoryNotExists
import com.panomc.platform.error.MessageCantBeEmpty
import com.panomc.platform.error.TicketCategoryRequired
import com.panomc.platform.error.TitleCantBeEmpty
import com.panomc.platform.model.*
import com.panomc.platform.notification.NotificationManager
import com.panomc.platform.notification.type.panel.NewTicketNotification
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.RequestPredicate
import io.vertx.ext.web.validation.ValidationHandler
import com.panomc.platform.schema.dsl.Bodies
import com.panomc.platform.schema.dsl.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.*
import com.panomc.platform.util.UsageMode
import com.panomc.platform.schema.EndpointDoc

@Endpoint
class CreateTicketAPI(
    private val databaseManager: DatabaseManager,
    private val authProvider: AuthProvider,
    private val notificationManager: NotificationManager,
    private val configManager: ConfigManager
) : LoggedInApi() {
    override val usageModes = UsageMode.WITH_WEBSITE

    override val paths = listOf(Path("/tickets", RouteType.POST))

    override val doc = EndpointDoc(
        summary = "Opens a ticket with its first message.",
        tag = "tickets",
        response = objectSchema().requiredProperty("id", intSchema()),
        errors = listOf(
            TitleCantBeEmpty::class,
            MessageCantBeEmpty::class,
            CategoryNotExists::class,
            TicketCategoryRequired::class
        )
    )

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .body(
                Bodies.json(
                    objectSchema()
                        .requiredProperty("title", stringSchema())
                        .requiredProperty("message", stringSchema())
                        .requiredProperty("categoryId", numberSchema())
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

        var category: TicketCategory? = null

        if (categoryId != -1L) {
            // A category that is turned off takes no new ticket: to the form it is not there.
            category = databaseManager.ticketCategoryDao.getById(categoryId, sqlClient)

            if (category == null || !category.enabled) {
                throw CategoryNotExists()
            }
        } else if (!TicketSettings.noCategoryPossible(databaseManager, sqlClient)) {
            throw TicketCategoryRequired()
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

        // The category's own auto-reply (or the one for tickets without a category) comes first; without one, the
        // default of the ticket settings.
        val ownAutoReplyTemplateId = category?.autoReplyTemplateId
            ?: TicketSettings.noCategoryAutoReplyTemplateId(databaseManager, sqlClient)
        val autoReplyTemplateId = ownAutoReplyTemplateId.takeIf { it != -1L }
            ?: TicketSettings.defaultAutoReplyTemplateId(databaseManager, sqlClient)

        sendAutoReply(Ticket(id = id, title = title, categoryId = categoryId, userId = userId), autoReplyTemplateId, sqlClient)

        val username = databaseManager.userDao.getUsernameFromUserId(userId, sqlClient)!!

        WebhookCoreEvents.fire { ticketCreated(id, title, categoryId, userId, username, sqlClient) }

        notificationManager.sendNotificationToAllWithPermission(
            NewTicketNotification(
                id,
                username
            ),
            ManageTicketsPermission(),
            sqlClient
        )

        return Successful(
            mapOf(
                "id" to id
            )
        )
    }

    /**
     * The category's ready reply, sent by the site itself as the first answer. The ticket stays new: an admin still
     * has to look at it.
     */
    private suspend fun sendAutoReply(ticket: Ticket, templateId: Long, sqlClient: SqlClient) {
        if (templateId == -1L) {
            return
        }

        val template = databaseManager.ticketTemplateDao.getById(templateId, sqlClient) ?: return
        val values = TicketTemplateRenderer.values(ticket, null, databaseManager, configManager, sqlClient)

        databaseManager.ticketMessageDao.addMessage(
            TicketMessage(
                userId = -1,
                ticketId = ticket.id,
                message = TicketTemplateRenderer.render(template.body, values),
                panel = 1,
                system = true
            ),
            sqlClient
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