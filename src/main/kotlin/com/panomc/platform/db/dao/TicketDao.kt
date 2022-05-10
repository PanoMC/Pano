package com.panomc.platform.db.dao

import com.panomc.platform.db.Dao
import com.panomc.platform.db.model.Ticket
import com.panomc.platform.model.Result
import io.vertx.core.AsyncResult
import io.vertx.core.json.JsonArray
import io.vertx.sqlclient.SqlConnection

@Suppress("PARAMETER_NAME_CHANGED_ON_OVERRIDE")
interface TicketDao : Dao<Ticket> {
    fun count(sqlConnection: SqlConnection, handler: (count: Int?, asyncResult: AsyncResult<*>) -> Unit)

    fun countOfOpenTickets(sqlConnection: SqlConnection, handler: (count: Int?, asyncResult: AsyncResult<*>) -> Unit)

    fun getLast5Tickets(
        sqlConnection: SqlConnection,
        handler: (tickets: List<Ticket>?, asyncResult: AsyncResult<*>) -> Unit
    )

    fun getAllByPageAndPageType(
        page: Int,
        pageType: Int,
        sqlConnection: SqlConnection,
        handler: (tickets: List<Ticket>?, asyncResult: AsyncResult<*>) -> Unit
    )

    fun getAllByPageAndCategoryID(
        page: Int,
        categoryID: Int,
        sqlConnection: SqlConnection,
        handler: (tickets: List<Ticket>?, asyncResult: AsyncResult<*>) -> Unit
    )

    fun getAllByUserIDAndPage(
        userID: Int,
        page: Int,
        sqlConnection: SqlConnection,
        handler: (tickets: List<Ticket>?, asyncResult: AsyncResult<*>) -> Unit
    )

    fun getCountByPageType(
        pageType: Int,
        sqlConnection: SqlConnection,
        handler: (count: Int?, asyncResult: AsyncResult<*>) -> Unit
    )

    fun getByCategory(
        id: Int,
        sqlConnection: SqlConnection,
        handler: (tickets: List<Ticket>?, asyncResult: AsyncResult<*>) -> Unit
    )

    fun closeTickets(
        selectedTickets: JsonArray,
        sqlConnection: SqlConnection,
        handler: (result: Result?, asyncResult: AsyncResult<*>) -> Unit
    )

    fun countByCategory(
        id: Int,
        sqlConnection: SqlConnection,
        handler: (count: Int?, asyncResult: AsyncResult<*>) -> Unit
    )

    fun delete(
        ticketList: JsonArray,
        sqlConnection: SqlConnection,
        handler: (result: Result?, asyncResult: AsyncResult<*>) -> Unit
    )

    fun countByUserID(
        id: Int,
        sqlConnection: SqlConnection,
        handler: (count: Int?, asyncResult: AsyncResult<*>) -> Unit
    )

    fun getByID(
        id: Int,
        sqlConnection: SqlConnection,
        handler: (ticket: Ticket?, asyncResult: AsyncResult<*>) -> Unit
    )

    fun isExistsByID(
        id: Int,
        sqlConnection: SqlConnection,
        handler: (exists: Boolean?, asyncResult: AsyncResult<*>) -> Unit
    )

    fun makeStatus(
        id: Int,
        status: Int,
        sqlConnection: SqlConnection,
        handler: (result: Result?, asyncResult: AsyncResult<*>) -> Unit
    )

    fun updateLastUpdateDate(
        id: Int,
        date: Long,
        sqlConnection: SqlConnection,
        handler: (result: Result?, asyncResult: AsyncResult<*>) -> Unit
    )
}