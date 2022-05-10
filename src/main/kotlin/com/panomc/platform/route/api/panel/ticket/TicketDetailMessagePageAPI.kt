package com.panomc.platform.route.api.panel.ticket

import com.panomc.platform.ErrorCode
import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.TicketMessage
import com.panomc.platform.model.*
import com.panomc.platform.util.AuthProvider
import com.panomc.platform.util.SetupManager
import io.vertx.core.AsyncResult
import io.vertx.ext.web.RoutingContext
import io.vertx.sqlclient.SqlConnection

@Endpoint
class TicketDetailMessagePageAPI(
    private val databaseManager: DatabaseManager,
    setupManager: SetupManager,
    authProvider: AuthProvider
) : PanelApi(setupManager, authProvider) {
    override val routeType = RouteType.POST

    override val routes = arrayListOf("/api/panel/ticket/detail/message/page")

    override fun handler(context: RoutingContext, handler: (result: Result) -> Unit) {
        val data = context.bodyAsJson
        val id = data.getInteger("id")
        val lastMessageID = data.getInteger("lastMessageId")

        databaseManager.createConnection((this::createConnectionHandler)(handler, lastMessageID, id))
    }

    private fun createConnectionHandler(
        handler: (result: Result) -> Unit,
        lastMessageID: Int,
        id: Int
    ) = handler@{ sqlConnection: SqlConnection?, _: AsyncResult<SqlConnection> ->
        if (sqlConnection == null) {
            handler.invoke(Error(ErrorCode.CANT_CONNECT_DATABASE))

            return@handler
        }

        databaseManager.ticketDao.isExistsByID(
            id,
            sqlConnection,
            (this::isExistsByHandler)(handler, lastMessageID, id, sqlConnection)
        )
    }

    private fun isExistsByHandler(
        handler: (result: Result) -> Unit,
        lastMessageID: Int,
        id: Int,
        sqlConnection: SqlConnection
    ) =
        handler@{ exists: Boolean?, _: AsyncResult<*> ->
            if (exists == null) {
                databaseManager.closeConnection(sqlConnection) {
                    handler.invoke(Error(ErrorCode.UNKNOWN))
                }

                return@handler
            }

            if (!exists) {
                databaseManager.closeConnection(sqlConnection) {
                    handler.invoke(Error(ErrorCode.NOT_EXISTS))
                }

                return@handler
            }

            databaseManager.ticketMessageDao.getByTicketIDPageAndStartFromID(
                lastMessageID,
                id,
                sqlConnection,
                (this::getByTicketIDAndPageHandler)(handler, sqlConnection)
            )
        }

    private fun getByTicketIDAndPageHandler(
        handler: (result: Result) -> Unit,
        sqlConnection: SqlConnection
    ) = handler@{ messages: List<TicketMessage>?, _: AsyncResult<*> ->
        if (messages == null) {
            databaseManager.closeConnection(sqlConnection) {
                handler.invoke(Error(ErrorCode.UNKNOWN))
            }

            return@handler
        }

        val userIDList = mutableListOf<Int>()

        messages.forEach { message ->
            if (userIDList.indexOf(message.userID) == -1)
                userIDList.add(message.userID)
        }

        databaseManager.userDao.getUsernameByListOfID(
            userIDList,
            sqlConnection,
            (this::invokeHandler)(handler, sqlConnection, messages)
        )
    }

    private fun invokeHandler(
        handler: (result: Result) -> Unit,
        sqlConnection: SqlConnection,
        ticketMessages: List<TicketMessage>
    ) = handler@{ usernameList: Map<Int, String>?, _: AsyncResult<*> ->
        databaseManager.closeConnection(sqlConnection) {
            if (usernameList == null) {
                handler.invoke(Error(ErrorCode.UNKNOWN))

                return@closeConnection
            }

            val messages = mutableListOf<Map<String, Any?>>()

            ticketMessages.forEach { ticketMessage ->
                messages.add(
                    0,
                    mapOf(
                        "id" to ticketMessage.id,
                        "userID" to ticketMessage.userID,
                        "ticketID" to ticketMessage.ticketID,
                        "username" to usernameList[ticketMessage.userID],
                        "message" to ticketMessage.message,
                        "date" to ticketMessage.date,
                        "panel" to ticketMessage.panel
                    )
                )
            }

            handler.invoke(
                Successful(
                    mapOf(
                        "messages" to messages
                    )
                )
            )
        }
    }
}