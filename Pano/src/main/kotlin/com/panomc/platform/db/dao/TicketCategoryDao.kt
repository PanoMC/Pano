package com.panomc.platform.db.dao

import com.panomc.platform.db.Dao
import com.panomc.platform.db.model.TicketCategory
import io.vertx.sqlclient.SqlClient

abstract class TicketCategoryDao : Dao<TicketCategory>(TicketCategory::class.java) {
    abstract suspend fun getAll(
        sqlClient: SqlClient
    ): List<TicketCategory>

    abstract suspend fun existsById(
        id: Long,
        sqlClient: SqlClient
    ): Boolean

    abstract suspend fun existsByUrl(
        url: String,
        sqlClient: SqlClient
    ): Boolean

    abstract suspend fun deleteById(
        id: Long,
        sqlClient: SqlClient
    )

    abstract suspend fun add(
        ticketCategory: TicketCategory,
        sqlClient: SqlClient
    ): Long

    abstract suspend fun updateUrlById(
        id: Long,
        newUrl: String,
        sqlClient: SqlClient
    )

    abstract suspend fun update(
        ticketCategory: TicketCategory,
        sqlClient: SqlClient
    )

    abstract suspend fun count(sqlClient: SqlClient): Long

    abstract suspend fun getByPage(
        page: Long,
        sqlClient: SqlClient
    ): List<TicketCategory>

    abstract suspend fun getById(
        id: Long,
        sqlClient: SqlClient
    ): TicketCategory?

    abstract suspend fun getByUrl(
        url: String,
        sqlClient: SqlClient
    ): TicketCategory?

    abstract suspend fun getByIdList(
        ticketCategoryIdList: List<Long>,
        sqlClient: SqlClient
    ): Map<Long, TicketCategory>
}