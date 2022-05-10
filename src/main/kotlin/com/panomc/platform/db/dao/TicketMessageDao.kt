package com.panomc.platform.db.dao

import com.panomc.platform.db.Dao
import com.panomc.platform.db.model.TicketMessage
import com.panomc.platform.model.Result
import io.vertx.core.AsyncResult
import io.vertx.core.json.JsonArray
import io.vertx.sqlclient.SqlConnection

@Suppress("PARAMETER_NAME_CHANGED_ON_OVERRIDE")
interface TicketMessageDao : Dao<TicketMessage> {
    fun getByTicketIDAndPage(
        ticketID: Int,
        sqlConnection: SqlConnection,
        handler: (messages: List<TicketMessage>?, asyncResult: AsyncResult<*>) -> Unit
    )

    fun getByTicketIDPageAndStartFromID(
        lastMessageID: Int,
        ticketID: Int,
        sqlConnection: SqlConnection,
        handler: (messages: List<TicketMessage>?, asyncResult: AsyncResult<*>) -> Unit
    )

    fun getCountByTicketID(
        ticketID: Int,
        sqlConnection: SqlConnection,
        handler: (count: Int?, asyncResult: AsyncResult<*>) -> Unit
    )

    fun addMessage(
        ticketMessage: TicketMessage,
        sqlConnection: SqlConnection,
        handler: (result: Result?, asyncResult: AsyncResult<*>) -> Unit
    )

    fun deleteByTicketIDList(
        ticketIDList: JsonArray,
        sqlConnection: SqlConnection,
        handler: (result: Result?, asyncResult: AsyncResult<*>) -> Unit
    )

    fun getLastMessageByTicketID(
        ticketID: Int,
        sqlConnection: SqlConnection,
        handler: (ticketMessage: TicketMessage?, asyncResult: AsyncResult<*>) -> Unit
    )
}