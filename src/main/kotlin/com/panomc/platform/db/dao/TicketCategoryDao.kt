package com.panomc.platform.db.dao

import com.panomc.platform.db.Dao
import com.panomc.platform.db.model.TicketCategory
import io.vertx.sqlclient.SqlConnection

interface TicketCategoryDao : Dao<TicketCategory> {
    suspend fun getAll(
        sqlConnection: SqlConnection
    ): List<TicketCategory>

    suspend fun isExistsById(
        id: Long,
        sqlConnection: SqlConnection
    ): Boolean

    suspend fun isExistsByUrl(
        url: String,
        sqlConnection: SqlConnection
    ): Boolean

    suspend fun deleteById(
        id: Long,
        sqlConnection: SqlConnection
    )

    suspend fun add(
        ticketCategory: TicketCategory,
        sqlConnection: SqlConnection
    )

    suspend fun update(
        ticketCategory: TicketCategory,
        sqlConnection: SqlConnection
    )

    suspend fun count(sqlConnection: SqlConnection): Long

    suspend fun getByPage(
        page: Long,
        sqlConnection: SqlConnection
    ): List<TicketCategory>

    suspend fun getById(
        id: Long,
        sqlConnection: SqlConnection
    ): TicketCategory?

    suspend fun getByUrl(
        url: String,
        sqlConnection: SqlConnection
    ): TicketCategory?

    suspend fun getByIdList(
        ticketCategoryIdList: List<Long>,
        sqlConnection: SqlConnection
    ): Map<Long, TicketCategory>
}