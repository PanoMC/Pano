package com.panomc.platform.route.api.ticket

import com.panomc.platform.ErrorCode
import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.model.*
import com.panomc.platform.util.AuthProvider
import com.panomc.platform.util.SetupManager
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Parameters.param
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaParser
import io.vertx.json.schema.common.dsl.Schemas.numberSchema

@Endpoint
class GetTicketMessagesAPI(
    private val databaseManager: DatabaseManager,
    setupManager: SetupManager,
    private val authProvider: AuthProvider
) : LoggedInApi(setupManager, authProvider) {
    override val paths = listOf(Path("/api/tickets/:id/messages", RouteType.GET))

    override fun getValidationHandler(schemaParser: SchemaParser): ValidationHandler =
        ValidationHandlerBuilder.create(schemaParser)
            .pathParameter(param("id", numberSchema()))
            .queryParameter(param("lastMessageId", numberSchema()))
            .build()

    override suspend fun handler(context: RoutingContext): Result {
        val parameters = getParameters(context)
        val id = parameters.pathParameter("id").long
        val lastMessageId = parameters.queryParameter("lastMessageId").long
        val userId = authProvider.getUserIdFromRoutingContext(context)

        val sqlConnection = createConnection(databaseManager, context)

        val exists = databaseManager.ticketDao.isExistsById(id, sqlConnection)

        if (!exists) {
            throw Error(ErrorCode.NOT_EXISTS)
        }

        val isBelong = databaseManager.ticketDao.isBelongToUserIdsById(id, userId, sqlConnection)

        if (!isBelong) {
            throw Error(ErrorCode.NO_PERMISSION)
        }

        val isTicketMessageIdExists = databaseManager.ticketMessageDao.isExistsById(lastMessageId, sqlConnection)

        if (!isTicketMessageIdExists) {
            throw Error(ErrorCode.NOT_EXISTS)
        }

        val ticketMessages =
            databaseManager.ticketMessageDao.getByTicketIdPageAndStartFromId(lastMessageId, id, sqlConnection)

        val userIdList = mutableListOf<Long>()

        ticketMessages.forEach { message ->
            if (userIdList.indexOf(message.userId) == -1)
                userIdList.add(message.userId)
        }

        val usernameList = databaseManager.userDao.getUsernameByListOfId(userIdList, sqlConnection)

        val messages = mutableListOf<Map<String, Any?>>()

        ticketMessages.forEach { ticketMessage ->
            messages.add(
                0,
                mapOf(
                    "id" to ticketMessage.id,
                    "userID" to ticketMessage.userId,
                    "ticketID" to ticketMessage.ticketId,
                    "username" to usernameList[ticketMessage.userId],
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