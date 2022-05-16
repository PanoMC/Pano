package com.panomc.platform.db.dao

import com.panomc.platform.db.Dao
import com.panomc.platform.db.model.TicketMessage
import io.vertx.core.json.JsonArray
import io.vertx.sqlclient.SqlConnection

@Suppress("PARAMETER_NAME_CHANGED_ON_OVERRIDE")
interface TicketMessageDao : Dao<TicketMessage> {
    suspend fun getByTicketIDAndPage(
        ticketID: Int,
        sqlConnection: SqlConnection
    ): List<TicketMessage>

    suspend fun getByTicketIDPageAndStartFromID(
        lastMessageID: Int,
        ticketID: Int,
        sqlConnection: SqlConnection
    ): List<TicketMessage>

    suspend fun getCountByTicketID(
        ticketID: Int,
        sqlConnection: SqlConnection
    ): Int

    suspend fun addMessage(
        ticketMessage: TicketMessage,
        sqlConnection: SqlConnection
    ): Long

    suspend fun deleteByTicketIDList(
        ticketIDList: JsonArray,
        sqlConnection: SqlConnection
    )

    suspend fun getLastMessageByTicketID(
        ticketID: Int,
        sqlConnection: SqlConnection
    ): TicketMessage?
}