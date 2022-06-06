package com.panomc.platform.db.dao

import com.panomc.platform.db.Dao
import com.panomc.platform.db.model.TicketCategory
import io.vertx.sqlclient.SqlConnection

@Suppress("PARAMETER_NAME_CHANGED_ON_OVERRIDE")
interface TicketCategoryDao : Dao<TicketCategory> {
    suspend fun getAll(
        sqlConnection: SqlConnection
    ): List<TicketCategory>

    suspend fun isExistsById(
        id: Int,
        sqlConnection: SqlConnection
    ): Boolean

    suspend fun isExistsByUrl(
        url: String,
        sqlConnection: SqlConnection
    ): Boolean

    suspend fun deleteById(
        id: Int,
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

    suspend fun count(sqlConnection: SqlConnection): Int

    suspend fun getByPage(
        page: Int,
        sqlConnection: SqlConnection
    ): List<TicketCategory>

    suspend fun getById(
        id: Int,
        sqlConnection: SqlConnection
    ): TicketCategory?

    suspend fun getByUrl(
        url: String,
        sqlConnection: SqlConnection
    ): TicketCategory?

    suspend fun getByIdList(
        ticketCategoryIdList: List<Int>,
        sqlConnection: SqlConnection
    ): Map<Int, TicketCategory>
}