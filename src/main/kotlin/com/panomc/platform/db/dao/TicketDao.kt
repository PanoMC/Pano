package com.panomc.platform.db.dao

import com.panomc.platform.db.Dao
import com.panomc.platform.db.model.Ticket
import com.panomc.platform.util.TicketPageType
import io.vertx.core.json.JsonArray
import io.vertx.sqlclient.SqlConnection

@Suppress("PARAMETER_NAME_CHANGED_ON_OVERRIDE")
interface TicketDao : Dao<Ticket> {
    suspend fun count(sqlConnection: SqlConnection): Int

    suspend fun countOfOpenTickets(sqlConnection: SqlConnection): Int

    suspend fun getLast5Tickets(
        sqlConnection: SqlConnection
    ): List<Ticket>

    suspend fun getAllByPageAndPageType(
        page: Int,
        pageType: TicketPageType,
        sqlConnection: SqlConnection
    ): List<Ticket>

    suspend fun getAllByPagePageTypeAndUserId(
        userId: Int,
        page: Int,
        pageType: TicketPageType,
        sqlConnection: SqlConnection
    ): List<Ticket>

    suspend fun getAllByPageAndCategoryId(
        page: Int,
        categoryId: Int,
        sqlConnection: SqlConnection
    ): List<Ticket>

    suspend fun getAllByUserIdAndPage(
        userId: Int,
        page: Int,
        sqlConnection: SqlConnection
    ): List<Ticket>

    suspend fun getCountByPageType(
        pageType: TicketPageType,
        sqlConnection: SqlConnection
    ): Int

    suspend fun getCountByPageTypeAndUserId(
        userId: Int,
        pageType: TicketPageType,
        sqlConnection: SqlConnection
    ): Int

    suspend fun getByCategory(
        id: Int,
        sqlConnection: SqlConnection
    ): List<Ticket>

    suspend fun closeTickets(
        selectedTickets: JsonArray,
        sqlConnection: SqlConnection
    )

    suspend fun countByCategory(
        id: Int,
        sqlConnection: SqlConnection
    ): Int

    suspend fun delete(
        ticketList: JsonArray,
        sqlConnection: SqlConnection
    )

    suspend fun countByUserId(
        id: Int,
        sqlConnection: SqlConnection
    ): Int

    suspend fun getById(
        id: Int,
        sqlConnection: SqlConnection
    ): Ticket?

    suspend fun isExistsById(
        id: Int,
        sqlConnection: SqlConnection
    ): Boolean

    suspend fun isExistsByIdAndUserId(
        id: Int,
        userId: Int,
        sqlConnection: SqlConnection
    ): Boolean

    suspend fun makeStatus(
        id: Int,
        status: Int,
        sqlConnection: SqlConnection
    )

    suspend fun updateLastUpdateDate(
        id: Int,
        date: Long,
        sqlConnection: SqlConnection
    )
}