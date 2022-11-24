package com.panomc.platform.route.api.panel.ticket

import com.panomc.platform.ErrorCode
import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.Ticket
import com.panomc.platform.db.model.TicketCategory
import com.panomc.platform.model.*
import com.panomc.platform.util.AuthProvider
import com.panomc.platform.util.SetupManager
import com.panomc.platform.util.TicketStatus
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Parameters.optionalParam
import io.vertx.json.schema.SchemaParser
import io.vertx.json.schema.common.dsl.Schemas.*
import kotlin.math.ceil

@Endpoint
class PanelGetTicketsAPI(
    private val databaseManager: DatabaseManager,
    setupManager: SetupManager,
    authProvider: AuthProvider
) : PanelApi(setupManager, authProvider) {
    override val paths = listOf(Path("/api/panel/tickets", RouteType.GET))

    override fun getValidationHandler(schemaParser: SchemaParser): ValidationHandler =
        ValidationHandler.builder(schemaParser)
            .queryParameter(
                optionalParam(
                    "pageType", arraySchema().items(enumSchema(*TicketStatus.values().map { it.status }.toTypedArray()))
                )
            )
            .queryParameter(optionalParam("page", numberSchema()))
            .queryParameter(optionalParam("categoryUrl", stringSchema()))
            .build()

    override suspend fun handler(context: RoutingContext): Result {
        val parameters = getParameters(context)

        val pageType =
            TicketStatus.valueOf(status = parameters.queryParameter("pageType")?.jsonArray?.first() as String? ?: "all")
                ?: TicketStatus.ALL
        val page = parameters.queryParameter("page")?.long ?: 1L
        val categoryUrl = parameters.queryParameter("categoryUrl")?.string

        var ticketCategory: TicketCategory? = null

        val sqlConnection = createConnection(databaseManager, context)

        if (categoryUrl != null && categoryUrl != "-") {
            val exists = databaseManager.ticketCategoryDao.isExistsByUrl(
                categoryUrl,
                sqlConnection
            )

            if (!exists) {
                throw Error(ErrorCode.NOT_EXISTS)
            }

            ticketCategory = databaseManager.ticketCategoryDao.getByUrl(categoryUrl, sqlConnection)
                ?: throw Error(ErrorCode.UNKNOWN)
        }

        if (categoryUrl != null && categoryUrl == "-") {
            ticketCategory = TicketCategory()
        }

        val count = if (ticketCategory != null)
            databaseManager.ticketDao.countByCategory(ticketCategory.id, sqlConnection)
        else
            databaseManager.ticketDao.getCountByPageType(pageType, sqlConnection)

        var totalPage = ceil(count.toDouble() / 10).toLong()

        if (totalPage < 1)
            totalPage = 1

        if (page > totalPage || page < 1) {
            throw Error(ErrorCode.PAGE_NOT_FOUND)
        }

        val tickets = if (ticketCategory != null)
            databaseManager.ticketDao.getAllByPageAndCategoryId(page, ticketCategory.id, sqlConnection)
        else
            databaseManager.ticketDao.getAllByPageAndPageType(page, pageType, sqlConnection)

        if (tickets.isEmpty()) {
            return getResults(ticketCategory, tickets, mapOf(), mapOf(), count, totalPage)
        }

        val userIdList = tickets.distinctBy { it.userId }.map { it.userId }

        val usernameList = databaseManager.userDao.getUsernameByListOfId(userIdList, sqlConnection)

        if (ticketCategory != null) {
            return getResults(ticketCategory, tickets, mapOf(), usernameList, count, totalPage)
        }

        val categoryIdList =
            tickets.filter { it.categoryId != -1L }.distinctBy { it.categoryId }.map { it.categoryId }

        if (categoryIdList.isEmpty()) {
            return getResults(null, tickets, mapOf(), usernameList, count, totalPage)
        }

        val ticketCategoryList = databaseManager.ticketCategoryDao.getByIdList(categoryIdList, sqlConnection)

        return getResults(null, tickets, ticketCategoryList, usernameList, count, totalPage)
    }

    private fun getResults(
        ticketCategory: TicketCategory?,
        tickets: List<Ticket>,
        ticketCategoryList: Map<Long, TicketCategory>,
        usernameList: Map<Long, String>,
        count: Long,
        totalPage: Long
    ): Result {
        val ticketDataList = mutableListOf<Map<String, Any?>>()

        tickets.forEach { ticket ->
            ticketDataList.add(
                mapOf(
                    "id" to ticket.id,
                    "title" to ticket.title,
                    "category" to (ticketCategory ?: if (ticket.categoryId == -1L)
                        mapOf("id" to -1, "title" to "-", "url" to "-")
                    else
                        ticketCategoryList.getOrDefault(
                            ticket.categoryId,
                            mapOf("id" to -1, "title" to "-", "url" to "-")
                        )),
                    "writer" to mapOf(
                        "username" to usernameList[ticket.userId]
                    ),
                    "date" to ticket.date,
                    "lastUpdate" to ticket.lastUpdate,
                    "status" to ticket.status.value
                )
            )
        }

        val result = mutableMapOf<String, Any?>(
            "tickets" to ticketDataList,
            "ticketCount" to count,
            "totalPage" to totalPage
        )

        if (ticketCategory != null) {
            result["category"] = ticketCategory
        }

        return Successful(result)
    }
}