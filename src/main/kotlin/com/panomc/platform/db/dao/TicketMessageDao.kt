package com.panomc.platform.db.dao

import com.panomc.platform.db.Dao
import com.panomc.platform.db.model.TicketMessage
import io.vertx.core.json.JsonArray
import io.vertx.sqlclient.SqlConnection

interface TicketMessageDao : Dao<TicketMessage> {
    suspend fun getByTicketIdAndPage(
        ticketId: Long,
        sqlConnection: SqlConnection
    ): List<TicketMessage>

    suspend fun getByTicketIdPageAndStartFromId(
        lastMessageId: Long,
        ticketId: Long,
        sqlConnection: SqlConnection
    ): List<TicketMessage>

    suspend fun getCountByTicketId(
        ticketId: Long,
        sqlConnection: SqlConnection
    ): Long

    suspend fun addMessage(
        ticketMessage: TicketMessage,
        sqlConnection: SqlConnection
    ): Long

    suspend fun deleteByTicketIdList(
        ticketIdList: JsonArray,
        sqlConnection: SqlConnection
    )

    suspend fun getLastMessageByTicketId(
        ticketId: Long,
        sqlConnection: SqlConnection
    ): TicketMessage?

    suspend fun isExistsById(
        id: Long,
        sqlConnection: SqlConnection
    ): Boolean
}