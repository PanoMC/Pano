package com.panomc.platform.db.dao

import com.panomc.platform.db.Dao
import com.panomc.platform.db.model.TicketMessage
import io.vertx.core.json.JsonArray
import io.vertx.sqlclient.SqlClient

abstract class TicketMessageDao : Dao<TicketMessage>(TicketMessage::class.java) {
    abstract suspend fun getByTicketId(
        ticketId: Long,
        sqlClient: SqlClient
    ): List<TicketMessage>

    abstract suspend fun getByTicketIdAndStartFromId(
        lastMessageId: Long,
        ticketId: Long,
        sqlClient: SqlClient
    ): List<TicketMessage>

    abstract suspend fun getCountByTicketId(
        ticketId: Long,
        sqlClient: SqlClient
    ): Long

    abstract suspend fun addMessage(
        ticketMessage: TicketMessage,
        sqlClient: SqlClient
    ): Long

    abstract suspend fun deleteByTicketIdList(
        ticketIdList: JsonArray,
        sqlClient: SqlClient
    )

    abstract suspend fun getLastMessageByTicketId(
        ticketId: Long,
        sqlClient: SqlClient
    ): TicketMessage?

    abstract suspend fun existsById(
        id: Long,
        sqlClient: SqlClient
    ): Boolean

    abstract suspend fun updateUserIdByUserId(
        userId: Long,
        newUserId: Long,
        sqlClient: SqlClient
    )
}