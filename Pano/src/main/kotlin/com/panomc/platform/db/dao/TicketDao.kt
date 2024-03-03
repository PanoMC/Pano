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

    abstract suspend fun getAllByPageAndPageType(
        page: Long,
        pageType: TicketPageType,
        sqlClient: SqlClient
    ): List<Ticket>

    abstract suspend fun getAllByPagePageTypeAndUserId(
        userId: Long,
        page: Long,
        pageType: TicketPageType,
        sqlClient: SqlClient
    ): List<Ticket>

    abstract suspend fun getAllByPageAndCategoryId(
        page: Long,
        categoryId: Long,
        sqlClient: SqlClient
    ): List<Ticket>

    abstract suspend fun getAllByPageCategoryIdAndUserId(
        page: Long,
        categoryId: Long,
        userId: Long,
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