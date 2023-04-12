package com.panomc.platform.db.dao

import com.panomc.platform.db.Dao
import com.panomc.platform.db.model.TicketCategory
import io.vertx.sqlclient.SqlClient

interface TicketCategoryDao : Dao<TicketCategory> {
    suspend fun getAll(
        sqlClient: SqlClient
    ): List<TicketCategory>

    suspend fun existsById(
        id: Long,
        sqlClient: SqlClient
    ): Boolean

    suspend fun existsByUrl(
        url: String,
        sqlClient: SqlClient
    ): Boolean

    suspend fun deleteById(
        id: Long,
        sqlClient: SqlClient
    )

    suspend fun add(
        ticketCategory: TicketCategory,
        sqlClient: SqlClient
    ): Long

    suspend fun updateUrlById(
        id: Long,
        newUrl: String,
        sqlClient: SqlClient
    )

    suspend fun update(
        ticketCategory: TicketCategory,
        sqlClient: SqlClient
    )

    suspend fun count(sqlClient: SqlClient): Long

    suspend fun getByPage(
        page: Long,
        sqlClient: SqlClient
    ): List<TicketCategory>

    suspend fun getById(
        id: Long,
        sqlClient: SqlClient
    ): TicketCategory?

    suspend fun getByUrl(
        url: String,
        sqlClient: SqlClient
    ): TicketCategory?

    suspend fun getByIdList(
        ticketCategoryIdList: List<Long>,
        sqlClient: SqlClient
    ): Map<Long, TicketCategory>
}