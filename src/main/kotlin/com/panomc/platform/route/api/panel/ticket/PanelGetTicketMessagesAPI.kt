package com.panomc.platform.route.api.panel.ticket


import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.PanelPermission
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.error.NotExists
import com.panomc.platform.model.*
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Parameters.param
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaParser
import io.vertx.json.schema.common.dsl.Schemas.numberSchema

@Endpoint
class PanelGetTicketMessagesAPI(
    private val databaseManager: DatabaseManager,
    private val authProvider: AuthProvider
) : PanelApi() {
    override val paths = listOf(Path("/api/panel/tickets/:id/messages", RouteType.GET))

    override fun getValidationHandler(schemaParser: SchemaParser): ValidationHandler =
        ValidationHandlerBuilder.create(schemaParser)
            .pathParameter(param("id", numberSchema()))
            .queryParameter(param("lastMessageId", numberSchema()))
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        authProvider.requirePermission(PanelPermission.MANAGE_TICKETS, context)

        val parameters = getParameters(context)
        val id = parameters.pathParameter("id").long
        val lastMessageId = parameters.queryParameter("lastMessageId").long

        val sqlClient = getSqlClient()

        val exists = databaseManager.ticketDao.existsById(id, sqlClient)

        if (!exists) {
            throw NotExists()
        }

        val isTicketMessageIdExists = databaseManager.ticketMessageDao.existsById(lastMessageId, sqlClient)

        if (!isTicketMessageIdExists) {
            throw NotExists()
        }

        val ticketMessages =
            databaseManager.ticketMessageDao.getByTicketIdAndStartFromId(lastMessageId, id, sqlClient)

        val userIdList = mutableListOf<Long>()

        ticketMessages
            .filter { ticketMessage -> ticketMessage.userId != -1L }
            .forEach { message ->
                if (userIdList.indexOf(message.userId) == -1)
                    userIdList.add(message.userId)
            }

        val usernameList = databaseManager.userDao.getUsernameByListOfId(userIdList, sqlClient)

        val messages = mutableListOf<Map<String, Any?>>()

        ticketMessages.forEach { ticketMessage ->
            messages.add(
                0,
                mapOf(
                    "id" to ticketMessage.id,
                    "userId" to ticketMessage.userId,
                    "ticketId" to ticketMessage.ticketId,
                    "username" to (usernameList[ticketMessage.userId] ?: "-"),
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