package com.panomc.platform.db.dao

import com.panomc.platform.db.Dao
import com.panomc.platform.db.model.Ticket
import com.panomc.platform.util.DashboardPeriodType
import com.panomc.platform.util.TicketStatus
import io.vertx.core.json.JsonArray
import io.vertx.sqlclient.SqlClient

interface TicketDao : Dao<Ticket> {
    suspend fun count(sqlClient: SqlClient): Long

    suspend fun countOfOpenTickets(sqlClient: SqlClient): Long

    suspend fun getLast5Tickets(
        sqlClient: SqlClient
    ): List<Ticket>

    suspend fun getAllByPageAndPageType(
        page: Long,
        pageType: TicketStatus,
        sqlClient: SqlClient
    ): List<Ticket>

    suspend fun getAllByPagePageTypeAndUserId(
        userId: Long,
        page: Long,
        pageType: TicketStatus,
        sqlClient: SqlClient
    ): List<Ticket>

    suspend fun getAllByPageAndCategoryId(
        page: Long,
        categoryId: Long,
        sqlClient: SqlClient
    ): List<Ticket>

    suspend fun getAllByPageCategoryIdAndUserId(
        page: Long,
        categoryId: Long,
        userId: Long,
        sqlClient: SqlClient
    ): List<Ticket>

    suspend fun getAllByUserIdAndPage(
        userId: Long,
        page: Long,
        sqlClient: SqlClient
    ): List<Ticket>

    suspend fun getCountByPageType(
        pageType: TicketStatus,
        sqlClient: SqlClient
    ): Long

    suspend fun getCountByPageTypeAndUserId(
        userId: Long,
        pageType: TicketStatus,
        sqlClient: SqlClient
    ): Long

    suspend fun getByCategory(
        id: Long,
        sqlClient: SqlClient
    ): List<Ticket>

    suspend fun closeTickets(
        selectedTickets: JsonArray,
        sqlClient: SqlClient
    )

    suspend fun closeTicketById(
        id: Long,
        sqlClient: SqlClient
    )

    suspend fun countByCategory(
        id: Long,
        sqlClient: SqlClient
    ): Long

    suspend fun countByCategoryAndUserId(
        id: Long,
        userId: Long,
        sqlClient: SqlClient
    ): Long

    suspend fun delete(
        ticketList: JsonArray,
        sqlClient: SqlClient
    )

    suspend fun countByUserId(
        id: Long,
        sqlClient: SqlClient
    ): Long

    suspend fun getById(
        id: Long,
        sqlClient: SqlClient
    ): Ticket?

    suspend fun existsById(
        id: Long,
        sqlClient: SqlClient
    ): Boolean

    suspend fun isIdBelongToUserId(
        id: Long,
        userId: Long,
        sqlClient: SqlClient
    ): Boolean

    suspend fun existsByIdAndUserId(
        id: Long,
        userId: Long,
        sqlClient: SqlClient
    ): Boolean

    suspend fun makeStatus(
        id: Long,
        status: Int,
        sqlClient: SqlClient
    )

    suspend fun updateLastUpdateDate(
        id: Long,
        date: Long,
        sqlClient: SqlClient
    )

    suspend fun add(
        ticket: Ticket,
        sqlClient: SqlClient
    ): Long

    suspend fun getDatesByPeriod(
        dashboardPeriodType: DashboardPeriodType,
        sqlClient: SqlClient
    ): List<Long>

    suspend fun areIdListExist(
        ids: List<Long>,
        sqlClient: SqlClient
    ): Boolean

    suspend fun removeTicketCategoriesByCategoryId(
        categoryId: Long,
        sqlClient: SqlClient
    )

    suspend fun getByUserId(
        userId: Long,
        sqlClient: SqlClient
    ): List<Ticket>

    suspend fun getStatusById(
        id: Long,
        sqlClient: SqlClient
    ): TicketStatus?
}