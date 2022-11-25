package com.panomc.platform.db.dao

import com.panomc.platform.db.Dao
import com.panomc.platform.db.model.Ticket
import com.panomc.platform.util.DashboardPeriodType
import com.panomc.platform.util.TicketStatus
import io.vertx.core.json.JsonArray
import io.vertx.sqlclient.SqlConnection

interface TicketDao : Dao<Ticket> {
    suspend fun count(sqlConnection: SqlConnection): Long

    suspend fun countOfOpenTickets(sqlConnection: SqlConnection): Long

    suspend fun getLast5Tickets(
        sqlConnection: SqlConnection
    ): List<Ticket>

    suspend fun getAllByPageAndPageType(
        page: Long,
        pageType: TicketStatus,
        sqlConnection: SqlConnection
    ): List<Ticket>

    suspend fun getAllByPagePageTypeAndUserId(
        userId: Long,
        page: Long,
        pageType: TicketStatus,
        sqlConnection: SqlConnection
    ): List<Ticket>

    suspend fun getAllByPageAndCategoryId(
        page: Long,
        categoryId: Long,
        sqlConnection: SqlConnection
    ): List<Ticket>

    suspend fun getAllByPageCategoryIdAndUserId(
        page: Long,
        categoryId: Long,
        userId: Long,
        sqlConnection: SqlConnection
    ): List<Ticket>

    suspend fun getAllByUserIdAndPage(
        userId: Long,
        page: Long,
        sqlConnection: SqlConnection
    ): List<Ticket>

    suspend fun getCountByPageType(
        pageType: TicketStatus,
        sqlConnection: SqlConnection
    ): Long

    suspend fun getCountByPageTypeAndUserId(
        userId: Long,
        pageType: TicketStatus,
        sqlConnection: SqlConnection
    ): Long

    suspend fun getByCategory(
        id: Long,
        sqlConnection: SqlConnection
    ): List<Ticket>

    suspend fun closeTickets(
        selectedTickets: JsonArray,
        sqlConnection: SqlConnection
    )

    suspend fun closeTicketById(
        id: Long,
        sqlConnection: SqlConnection
    )

    suspend fun countByCategory(
        id: Long,
        sqlConnection: SqlConnection
    ): Long

    suspend fun countByCategoryAndUserId(
        id: Long,
        userId: Long,
        sqlConnection: SqlConnection
    ): Long

    suspend fun delete(
        ticketList: JsonArray,
        sqlConnection: SqlConnection
    )

    suspend fun countByUserId(
        id: Long,
        sqlConnection: SqlConnection
    ): Long

    suspend fun getById(
        id: Long,
        sqlConnection: SqlConnection
    ): Ticket?

    suspend fun isExistsById(
        id: Long,
        sqlConnection: SqlConnection
    ): Boolean

    suspend fun isBelongToUserIdsById(
        id: Long,
        userId: Long,
        sqlConnection: SqlConnection
    ): Boolean

    suspend fun isExistsByIdAndUserId(
        id: Long,
        userId: Long,
        sqlConnection: SqlConnection
    ): Boolean

    suspend fun makeStatus(
        id: Long,
        status: Int,
        sqlConnection: SqlConnection
    )

    suspend fun updateLastUpdateDate(
        id: Long,
        date: Long,
        sqlConnection: SqlConnection
    )

    suspend fun save(
        ticket: Ticket,
        sqlConnection: SqlConnection
    ): Long

    suspend fun getDatesByPeriod(
        dashboardPeriodType: DashboardPeriodType,
        sqlConnection: SqlConnection
    ): List<Long>
}