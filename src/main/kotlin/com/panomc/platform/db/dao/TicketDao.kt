package com.panomc.platform.db.dao

import com.panomc.platform.db.Dao
import com.panomc.platform.db.model.Ticket
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
        pageType: Int,
        sqlConnection: SqlConnection
    ): List<Ticket>

    suspend fun getAllByPageAndCategoryID(
        page: Int,
        categoryID: Int,
        sqlConnection: SqlConnection
    ): List<Ticket>

    suspend fun getAllByUserIDAndPage(
        userID: Int,
        page: Int,
        sqlConnection: SqlConnection
    ): List<Ticket>

    suspend fun getCountByPageType(
        pageType: Int,
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

    suspend fun countByUserID(
        id: Int,
        sqlConnection: SqlConnection
    ): Int

    suspend fun getByID(
        id: Int,
        sqlConnection: SqlConnection
    ): Ticket?

    suspend fun isExistsByID(
        id: Int,
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