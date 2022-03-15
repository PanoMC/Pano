package com.panomc.platform.route.api.panel.ticket

import com.panomc.platform.ErrorCode
import com.panomc.platform.db.model.Ticket
import com.panomc.platform.db.model.TicketCategory
import com.panomc.platform.model.*
import io.vertx.core.AsyncResult
import io.vertx.ext.web.RoutingContext
import io.vertx.sqlclient.SqlConnection
import kotlin.math.ceil

class TicketCategoryPageInitAPI : PanelApi() {
    override val routeType = RouteType.POST

    override val routes = arrayListOf("/api/panel/initPage/tickets/categoryPage")

    override fun handler(context: RoutingContext, handler: (result: Result) -> Unit) {
        val data = context.bodyAsJson
        val page = data.getInteger("page")

        databaseManager.createConnection((this::createConnectionHandler)(handler, page))
    }

    private fun createConnectionHandler(
        handler: (result: Result) -> Unit,
        page: Int,
    ) = handler@{ sqlConnection: SqlConnection?, _: AsyncResult<SqlConnection> ->
        if (sqlConnection == null) {
            handler.invoke(Error(ErrorCode.CANT_CONNECT_DATABASE))

            return@handler
        }

        databaseManager.getDatabase().ticketCategoryDao.count(
            sqlConnection,
            (this::countHandler)(handler, sqlConnection, page)
        )
    }

    private fun countHandler(
        handler: (result: Result) -> Unit,
        sqlConnection: SqlConnection,
        page: Int
    ) = handler@{ count: Int?, _: AsyncResult<*> ->
        if (count == null) {
            databaseManager.closeConnection(sqlConnection) {
                handler.invoke(Error(ErrorCode.UNKNOWN))
            }

            return@handler
        }

        var totalPage = ceil(count.toDouble() / 10).toInt()

        if (totalPage < 1)
            totalPage = 1

        if (page > totalPage || page < 1) {
            databaseManager.closeConnection(sqlConnection) {
                handler.invoke(Error(ErrorCode.PAGE_NOT_FOUND))
            }

            return@handler
        }

        databaseManager.getDatabase().ticketCategoryDao.getByPage(
            page,
            sqlConnection,
            (this::getByPageHandler)(handler, sqlConnection, count, totalPage)
        )
    }

    private fun getByPageHandler(
        handler: (result: Result) -> Unit,
        sqlConnection: SqlConnection,
        count: Int,
        totalPage: Int
    ) = handler@{ categories: List<TicketCategory>?, _: AsyncResult<*> ->
        if (categories == null) {
            databaseManager.closeConnection(sqlConnection) {
                handler.invoke(Error(ErrorCode.UNKNOWN))
            }

            return@handler
        }

        val categoriesDataList = mutableListOf<Map<String, Any?>>()

        val handlers: List<(handler: () -> Unit) -> Any> =
            categories.map { category ->
                val localHandler: (handler: () -> Unit) -> Any = { localHandler ->
                    databaseManager.getDatabase().ticketDao.countByCategory(
                        category.id,
                        sqlConnection,
                        (this::countByCategoryHandler)(
                            handler,
                            sqlConnection,
                            category,
                            localHandler,
                            categoriesDataList
                        )
                    )
                }

                localHandler
            }

        var currentIndex = -1

        fun invoke() {
            val localHandler: () -> Unit = {
                if (currentIndex == handlers.lastIndex)
                    returnResult(handler, sqlConnection, categoriesDataList, count, totalPage)
                else
                    invoke()
            }

            currentIndex++

            if (currentIndex <= handlers.lastIndex)
                handlers[currentIndex].invoke(localHandler)
        }

        if (categories.isNotEmpty()) {
            invoke()

            return@handler
        }

        returnResult(handler, sqlConnection, categoriesDataList, count, totalPage)
    }

    private fun returnResult(
        handler: (result: Result) -> Unit,
        sqlConnection: SqlConnection,
        categoryDataList: MutableList<Map<String, Any?>>,
        count: Int,
        totalPage: Int
    ) {
        databaseManager.closeConnection(sqlConnection) {
            handler.invoke(
                Successful(
                    mutableMapOf<String, Any?>(
                        "categories" to categoryDataList,
                        "categoryCount" to count,
                        "totalPage" to totalPage,
                        "host" to "http://"
                    )
                )
            )
        }
    }

    private fun countByCategoryHandler(
        handler: (result: Result) -> Unit,
        sqlConnection: SqlConnection,
        category: TicketCategory,
        localHandler: () -> Unit,
        categoryDataList: MutableList<Map<String, Any?>>
    ) = handler@{ count: Int?, _: AsyncResult<*> ->
        if (count == null) {
            databaseManager.closeConnection(sqlConnection) {
                handler.invoke(Error(ErrorCode.UNKNOWN))
            }

            return@handler
        }

        databaseManager.getDatabase().ticketDao.getByCategory(
            category.id,
            sqlConnection,
            (this::getByCategoryHandler)(handler, sqlConnection, category, count, categoryDataList, localHandler)
        )
    }

    private fun getByCategoryHandler(
        handler: (result: Result) -> Unit,
        sqlConnection: SqlConnection,
        category: TicketCategory,
        count: Int,
        categoryDataList: MutableList<Map<String, Any?>>,
        localHandler: () -> Unit
    ) = handler@{ tickets: List<Ticket>?, _: AsyncResult<*> ->
        if (tickets == null) {
            databaseManager.closeConnection(sqlConnection) {
                handler.invoke(Error(ErrorCode.UNKNOWN))
            }

            return@handler
        }

        val ticketDataList = mutableListOf<Map<String, Any?>>()

        tickets.forEach { ticket ->
            ticketDataList.add(
                mapOf(
                    "id" to ticket.id,
                    "title" to ticket.title
                )
            )
        }

        categoryDataList.add(
            mapOf(
                "id" to category.id,
                "title" to category.title,
                "description" to category.description,
                "ticketCount" to count,
                "tickets" to ticketDataList
            )
        )

        localHandler.invoke()
    }
}