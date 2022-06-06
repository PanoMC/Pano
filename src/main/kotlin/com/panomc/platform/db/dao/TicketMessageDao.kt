package com.panomc.platform.db.dao

import com.panomc.platform.db.Dao
import com.panomc.platform.db.model.TicketMessage
import io.vertx.core.json.JsonArray
import io.vertx.sqlclient.SqlConnection

@Suppress("PARAMETER_NAME_CHANGED_ON_OVERRIDE")
interface TicketMessageDao : Dao<TicketMessage> {
    suspend fun getByTicketIdAndPage(
        ticketId: Int,
        sqlConnection: SqlConnection
    ): List<TicketMessage>

    suspend fun getByTicketIdPageAndStartFromId(
        lastMessageId: Int,
        ticketId: Int,
        sqlConnection: SqlConnection
    ): List<TicketMessage>

    suspend fun getCountByTicketId(
        ticketId: Int,
        sqlConnection: SqlConnection
    ): Int

    suspend fun addMessage(
        ticketMessage: TicketMessage,
        sqlConnection: SqlConnection
    ): Long

    suspend fun deleteByTicketIdList(
        ticketIdList: JsonArray,
        sqlConnection: SqlConnection
    )

    suspend fun getLastMessageByTicketId(
        ticketId: Int,
        sqlConnection: SqlConnection
    ): TicketMessage?

    suspend fun isExistsById(
        id: Int,
        sqlConnection: SqlConnection
    ): Boolean
}