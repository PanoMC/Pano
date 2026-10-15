package com.panomc.platform.db.dao

import com.panomc.platform.db.Dao
import com.panomc.platform.db.model.Ticket
import com.panomc.platform.util.DashboardPeriodType
import com.panomc.platform.util.TicketPageType
import com.panomc.platform.util.TicketStatus
import io.vertx.core.json.JsonArray
import io.vertx.sqlclient.SqlClient

abstract class TicketDao : Dao<Ticket>(Ticket::class.java) {
    abstract suspend fun count(sqlClient: SqlClient): Long

    abstract suspend fun countOfOpenTickets(sqlClient: SqlClient): Long

    abstract suspend fun getLast5Tickets(
        sqlClient: SqlClient
    ): List<Ticket>

    abstract suspend fun getListByPageType(
        pageType: TicketPageType,
        limit: Int,
        offset: Long,
        sqlClient: SqlClient
    ): List<Ticket>

    abstract suspend fun getListByPageTypeAndSearch(
        pageType: TicketPageType,
        search: String,
        limit: Int,
        offset: Long,
        sqlClient: SqlClient
    ): List<Ticket>

    /** Tickets of one user filtered by [pageType]: [limit] rows after skipping [offset]. */
    abstract suspend fun getAllByUserIdAndPageType(
        userId: Long,
        pageType: TicketPageType,
        limit: Int,
        offset: Long,
        sqlClient: SqlClient
    ): List<Ticket>

    abstract suspend fun getListByCategoryId(
        categoryId: Long,
        limit: Int,
        offset: Long,
        sqlClient: SqlClient
    ): List<Ticket>

    /** Tickets of one user in one category: [limit] rows after skipping [offset]. */
    abstract suspend fun getAllByCategoryIdAndUserId(
        categoryId: Long,
        userId: Long,
        limit: Int,
        offset: Long,
        sqlClient: SqlClient
    ): List<Ticket>

    abstract suspend fun getAllByUserIdAndPage(
        userId: Long,
        page: Long,
        sqlClient: SqlClient
    ): List<Ticket>

    abstract suspend fun getCountByPageType(
        pageType: TicketPageType,
        sqlClient: SqlClient
    ): Long

    abstract suspend fun getCountByPageTypeAndUserId(
        userId: Long,
        pageType: TicketPageType,
        sqlClient: SqlClient
    ): Long

    abstract suspend fun getCountByPageTypeAndSearch(
        pageType: TicketPageType,
        search: String,
        sqlClient: SqlClient
    ): Long

    abstract suspend fun getByCategory(
        id: Long,
        sqlClient: SqlClient
    ): List<Ticket>

    abstract suspend fun closeTickets(
        selectedTickets: JsonArray,
        sqlClient: SqlClient
    )

    abstract suspend fun closeTicketById(
        id: Long,
        sqlClient: SqlClient
    )

    abstract suspend fun countByCategory(
        id: Long,
        sqlClient: SqlClient
    ): Long

    abstract suspend fun countByCategoryAndUserId(
        id: Long,
        userId: Long,
        sqlClient: SqlClient
    ): Long

    abstract suspend fun delete(
        ticketList: JsonArray,
        sqlClient: SqlClient
    )

    abstract suspend fun countByUserId(
        id: Long,
        sqlClient: SqlClient
    ): Long

    abstract suspend fun countByUserIdList(
        userIdList: List<Long>,
        sqlClient: SqlClient
    ): Map<Long, Long>

    abstract suspend fun getById(
        id: Long,
        sqlClient: SqlClient
    ): Ticket?

    abstract suspend fun existsById(
        id: Long,
        sqlClient: SqlClient
    ): Boolean

    abstract suspend fun isIdBelongToUserId(
        id: Long,
        userId: Long,
        sqlClient: SqlClient
    ): Boolean

    abstract suspend fun existsByIdAndUserId(
        id: Long,
        userId: Long,
        sqlClient: SqlClient
    ): Boolean

    abstract suspend fun makeStatus(
        id: Long,
        status: TicketStatus,
        sqlClient: SqlClient
    )

    abstract suspend fun updateLastUpdateDate(
        id: Long,
        date: Long,
        sqlClient: SqlClient
    )

    abstract suspend fun add(
        ticket: Ticket,
        sqlClient: SqlClient
    ): Long

    abstract suspend fun getDatesByPeriod(
        dashboardPeriodType: DashboardPeriodType,
        sqlClient: SqlClient
    ): List<Long>

    abstract suspend fun areIdListExist(
        ids: List<Long>,
        sqlClient: SqlClient
    ): Boolean

    abstract suspend fun removeTicketCategoriesByCategoryId(
        categoryId: Long,
        sqlClient: SqlClient
    )

    abstract suspend fun getByUserId(
        userId: Long,
        sqlClient: SqlClient
    ): List<Ticket>

    abstract suspend fun getStatusById(
        id: Long,
        sqlClient: SqlClient
    ): TicketStatus?
}