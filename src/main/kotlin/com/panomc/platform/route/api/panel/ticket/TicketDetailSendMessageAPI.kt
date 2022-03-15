package com.panomc.platform.route.api.panel.ticket

import com.panomc.platform.ErrorCode
import com.panomc.platform.db.model.TicketMessage
import com.panomc.platform.model.*
import io.vertx.core.AsyncResult
import io.vertx.ext.web.RoutingContext
import io.vertx.sqlclient.SqlConnection

class TicketDetailSendMessageAPI : PanelApi() {
    override val routeType = RouteType.POST

    override val routes = arrayListOf("/api/panel/ticket/detail/message/send")

    override fun handler(context: RoutingContext, handler: (result: Result) -> Unit) {
        val data = context.bodyAsJson

        val ticketID = data.getInteger("ticketId")
        val message = data.getString("message")

        val userID = authProvider.getUserIDFromRoutingContext(context)

        databaseManager.createConnection((this::createConnectionHandler)(handler, ticketID, message, userID))
    }

    private fun createConnectionHandler(
        handler: (result: Result) -> Unit,
        ticketID: Int,
        message: String,
        userID: Int
    ) = handler@{ sqlConnection: SqlConnection?, _: AsyncResult<SqlConnection> ->
        if (sqlConnection == null) {
            handler.invoke(Error(ErrorCode.CANT_CONNECT_DATABASE))

            return@handler
        }

        databaseManager.getDatabase().ticketDao.isExistsByID(
            ticketID,
            sqlConnection,
            (this::isExistsByHandler)(handler, ticketID, message, sqlConnection, userID)
        )
    }

    private fun isExistsByHandler(
        handler: (result: Result) -> Unit,
        ticketID: Int,
        message: String,
        sqlConnection: SqlConnection,
        userID: Int
    ) = handler@{ exists: Boolean?, _: AsyncResult<*> ->
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

        databaseManager.getDatabase().userDao.getUsernameFromUserID(
            userID,
            sqlConnection,
            (this::getUsernameFromUserIDHandler)(handler, sqlConnection, ticketID, message, userID)
        )
    }

    private fun getUsernameFromUserIDHandler(
        handler: (result: Result) -> Unit,
        sqlConnection: SqlConnection,
        ticketID: Int,
        message: String,
        userID: Int
    ) = handler@{ username: String?, _: AsyncResult<*> ->
        if (username == null) {
            databaseManager.closeConnection(sqlConnection) {
                handler.invoke(Error(ErrorCode.UNKNOWN))
            }

            return@handler
        }

        val ticketMessage = TicketMessage(-1, userID, ticketID, message, System.currentTimeMillis(), 1)

        databaseManager.getDatabase().ticketMessageDao.addMessage(
            ticketMessage,
            sqlConnection,
            (this::addMessageHandler)(handler, sqlConnection, ticketMessage, username)
        )
    }

    private fun addMessageHandler(
        handler: (result: Result) -> Unit,
        sqlConnection: SqlConnection,
        ticketMessage: TicketMessage,
        username: String
    ) = handler@{ result: Result?, _: AsyncResult<*> ->
        if (result == null) {
            databaseManager.closeConnection(sqlConnection) {
                handler.invoke(Error(ErrorCode.UNKNOWN))
            }

            return@handler
        }

        if (result is Successful)
            databaseManager.getDatabase().ticketDao.makeStatus(
                ticketMessage.ticketID,
                2,
                sqlConnection,
                (this::makeStatusHandler)(handler, sqlConnection, ticketMessage, username, result.map["id"] as Long)
            )
    }

    private fun makeStatusHandler(
        handler: (result: Result) -> Unit,
        sqlConnection: SqlConnection,
        ticketMessage: TicketMessage,
        username: String,
        lastInsertID: Long
    ) = handler@{ result: Result?, _: AsyncResult<*> ->
        if (result == null) {
            databaseManager.closeConnection(sqlConnection) {
                handler.invoke(Error(ErrorCode.UNKNOWN))
            }

            return@handler
        }

        databaseManager.getDatabase().ticketDao.updateLastUpdateDate(
            ticketMessage.ticketID,
            System.currentTimeMillis(),
            sqlConnection,
            (this::updateLastUpdateDateHandler)(handler, sqlConnection, ticketMessage, username, lastInsertID)
        )
    }

    private fun updateLastUpdateDateHandler(
        handler: (result: Result) -> Unit,
        sqlConnection: SqlConnection,
        ticketMessage: TicketMessage,
        username: String,
        lastInsertID: Long
    ) = handler@{ result: Result?, _: AsyncResult<*> ->
        databaseManager.closeConnection(sqlConnection) {
            if (result == null) {
                handler.invoke(Error(ErrorCode.UNKNOWN))

                return@closeConnection
            }

            handler.invoke(
                Successful(
                    mapOf(
                        "message" to mapOf(
                            "id" to lastInsertID,
                            "userID" to ticketMessage.userID,
                            "ticketID" to ticketMessage.ticketID,
                            "username" to username,
                            "message" to ticketMessage.message,
                            "date" to ticketMessage.date,
                            "panel" to ticketMessage.panel
                        )
                    )
                )
            )
        }
    }
}