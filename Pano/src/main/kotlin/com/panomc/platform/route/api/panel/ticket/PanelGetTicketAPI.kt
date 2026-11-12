package com.panomc.platform.route.api.panel.ticket


import com.panomc.platform.i18n.ContentTranslations
import com.panomc.platform.ticket.TicketFields
import com.panomc.platform.util.HtmlSanitizer
import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.panel.permission.ManageTicketsPermission
import com.panomc.platform.config.ConfigManager
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.Ticket
import com.panomc.platform.db.model.TicketCategory
import com.panomc.platform.db.model.TicketMessage
import com.panomc.platform.error.NotExists
import com.panomc.platform.model.*
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import com.panomc.platform.schema.dsl.Parameters.param
import com.panomc.platform.schema.dsl.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.numberSchema
import com.panomc.platform.util.UsageMode

@Endpoint
class PanelGetTicketAPI(
    private val databaseManager: DatabaseManager,
    private val authProvider: AuthProvider,
    private val configManager: ConfigManager,
    private val contentTranslations: ContentTranslations
) : PanelApi() {
    override val usageModes = UsageMode.WITH_WEBSITE

    override val paths = listOf(Path("/tickets/:id", RouteType.GET))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .pathParameter(param("id", numberSchema()))
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        authProvider.requirePermission(ManageTicketsPermission(), context)

        val parameters = getParameters(context)
        val id = parameters.pathParameter("id").long

        val sqlClient = getSqlClient()

        val exists = databaseManager.ticketDao.existsById(id, sqlClient)

        if (!exists) {
            throw NotExists()
        }

        val ticket = databaseManager.ticketDao.getById(id, sqlClient)!!
        val categoryTranslations = contentTranslations.loadCategories(listOf(ticket.categoryId), sqlClient)[ticket.categoryId]
        val fields = contentTranslations.withSnapshotI18n(
            ticket.categoryId, TicketFields.storedSnapshot(databaseManager.ticketFieldSetDao.getByTicketId(id, sqlClient)), categoryTranslations
        )
        val categoryI18n = contentTranslations.categoryI18n(ticket.categoryId, categoryTranslations)

        val username = databaseManager.userDao.getUsernameFromUserId(ticket.userId, sqlClient)!!

        val messages = databaseManager.ticketMessageDao.getByTicketId(id, sqlClient)

        val userIdList = mutableListOf<Long>()

        messages
            .filter { ticketMessage -> ticketMessage.userId != -1L }
            .forEach { message ->
                if (userIdList.indexOf(message.userId) == -1)
                    userIdList.add(message.userId)
            }

        val usernameList = databaseManager.userDao.getUsernameByListOfId(userIdList, sqlClient)

        val count = databaseManager.ticketMessageDao.getCountByTicketId(ticket.id, sqlClient)

        if (ticket.categoryId == -1L) {
            return getResult(ticket, usernameList, null, username, messages, count, fields, categoryI18n)
        }

        val ticketCategory = databaseManager.ticketCategoryDao.getById(ticket.categoryId, sqlClient)

        return getResult(ticket, usernameList, ticketCategory, username, messages, count, fields, categoryI18n)
    }

    private fun getResult(
        ticket: Ticket,
        usernameList: Map<Long, String>,
        ticketCategory: TicketCategory?,
        username: String,
        ticketMessages: List<TicketMessage>,
        messageCount: Long,
        fields: Any,
        categoryI18n: Any
    ): Result {
        val messages = mutableListOf<Map<String, Any?>>()

        ticketMessages.forEach { ticketMessage ->
            messages.add(
                0,
                mapOf(
                    "id" to ticketMessage.id,
                    "userId" to ticketMessage.userId,
                    "ticketId" to ticketMessage.ticketId,
                    // A message the site sent by itself (an auto-reply) is signed with the website's name.
                    "username" to (if (ticketMessage.system) configManager.config.websiteName
                    else usernameList[ticketMessage.userId] ?: "-"),
                    // Staff replies are rendered as HTML; a player's own message is shown as text.
                    "message" to if (ticketMessage.panel == 1) HtmlSanitizer.sanitize(ticketMessage.message) else ticketMessage.message,
                    "date" to ticketMessage.date,
                    "panel" to ticketMessage.panel,
                    "system" to ticketMessage.system
                )
            )
        }

        return Successful(
            mapOf(
                "ticket" to mapOf(
                    "username" to username,
                    "title" to ticket.title,
                    "category" to (ticketCategory ?: TicketCategory()).let { category ->
                        mapOf(
                            "id" to category.id,
                            "title" to category.title,
                            "description" to category.description,
                            "url" to category.url,
                            "enabled" to category.enabled,
                            "autoReplyTemplateId" to category.autoReplyTemplateId,
                            "i18n" to categoryI18n
                        )
                    },
                    "messages" to messages,
                    "status" to ticket.status,
                    "date" to ticket.date,
                    "count" to messageCount,
                    "fields" to fields
                )
            )
        )
    }
}