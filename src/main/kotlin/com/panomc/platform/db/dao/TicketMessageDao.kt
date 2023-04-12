package com.panomc.platform.db.dao

import com.panomc.platform.db.Dao
import com.panomc.platform.db.model.TicketMessage
import io.vertx.core.json.JsonArray
import io.vertx.sqlclient.SqlClient

interface TicketMessageDao : Dao<TicketMessage> {
    suspend fun getByTicketId(
        ticketId: Long,
        sqlClient: SqlClient
    ): List<TicketMessage>

    suspend fun getByTicketIdAndStartFromId(
        lastMessageId: Long,
        ticketId: Long,
        sqlClient: SqlClient
    ): List<TicketMessage>

    suspend fun getCountByTicketId(
        ticketId: Long,
        sqlClient: SqlClient
    ): Long

    suspend fun addMessage(
        ticketMessage: TicketMessage,
        sqlClient: SqlClient
    ): Long

    suspend fun deleteByTicketIdList(
        ticketIdList: JsonArray,
        sqlClient: SqlClient
    )

    suspend fun getLastMessageByTicketId(
        ticketId: Long,
        sqlClient: SqlClient
    ): TicketMessage?

    suspend fun existsById(
        id: Long,
        sqlClient: SqlClient
    ): Boolean

    suspend fun updateUserIdByUserId(
        userId: Long,
        newUserId: Long,
        sqlClient: SqlClient
    )
}