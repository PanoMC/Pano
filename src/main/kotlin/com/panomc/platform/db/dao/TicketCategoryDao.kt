package com.panomc.platform.db.dao

import com.panomc.platform.db.Dao
import com.panomc.platform.db.model.TicketCategory
import com.panomc.platform.model.Result
import io.vertx.core.AsyncResult
import io.vertx.sqlclient.SqlConnection

@Suppress("PARAMETER_NAME_CHANGED_ON_OVERRIDE")
interface TicketCategoryDao : Dao<TicketCategory> {
    fun getAll(
        sqlConnection: SqlConnection,
        handler: (categories: List<TicketCategory>?, asyncResult: AsyncResult<*>) -> Unit
    )

    fun isExistsByID(
        id: Int,
        sqlConnection: SqlConnection,
        handler: (exists: Boolean?, asyncResult: AsyncResult<*>) -> Unit
    )

    fun isExistsByURL(
        url: String,
        sqlConnection: SqlConnection,
        handler: (exists: Boolean?, asyncResult: AsyncResult<*>) -> Unit
    )

    fun deleteByID(
        id: Int,
        sqlConnection: SqlConnection,
        handler: (result: Result?, asyncResult: AsyncResult<*>) -> Unit
    )

    fun add(
        ticketCategory: TicketCategory,
        sqlConnection: SqlConnection,
        handler: (result: Result?, asyncResult: AsyncResult<*>) -> Unit
    )

    fun update(
        ticketCategory: TicketCategory,
        sqlConnection: SqlConnection,
        handler: (result: Result?, asyncResult: AsyncResult<*>) -> Unit
    )

    fun count(sqlConnection: SqlConnection, handler: (count: Int?, asyncResult: AsyncResult<*>) -> Unit)

    fun getByPage(
        page: Int,
        sqlConnection: SqlConnection,
        handler: (categories: List<TicketCategory>?, asyncResult: AsyncResult<*>) -> Unit
    )

    fun getByID(
        id: Int,
        sqlConnection: SqlConnection,
        handler: (ticketCategory: TicketCategory?, asyncResult: AsyncResult<*>) -> Unit
    )

    fun getByURL(
        url: String,
        sqlConnection: SqlConnection,
        handler: (ticketCategory: TicketCategory?, asyncResult: AsyncResult<*>) -> Unit
    )

    fun getByIDList(
        ticketCategoryIdList: List<Int>,
        sqlConnection: SqlConnection,
        handler: (ticketCategoryList: Map<Int, TicketCategory>?, asyncResult: AsyncResult<*>) -> Unit
    )
}