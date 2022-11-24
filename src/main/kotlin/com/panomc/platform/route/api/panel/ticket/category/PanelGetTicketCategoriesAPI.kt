package com.panomc.platform.route.api.panel.ticket.category

import com.panomc.platform.ErrorCode
import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.Ticket
import com.panomc.platform.db.model.TicketCategory
import com.panomc.platform.model.*
import com.panomc.platform.util.AuthProvider
import com.panomc.platform.util.SetupManager
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Parameters
import io.vertx.json.schema.SchemaParser
import io.vertx.json.schema.common.dsl.Schemas
import kotlin.math.ceil

@Endpoint
class PanelGetTicketCategoriesAPI(
    private val databaseManager: DatabaseManager,
    setupManager: SetupManager,
    authProvider: AuthProvider
) : PanelApi(setupManager, authProvider) {
    override val paths = listOf(Path("/api/panel/ticket/categories", RouteType.GET))

    override fun getValidationHandler(schemaParser: SchemaParser): ValidationHandler =
        ValidationHandler.builder(schemaParser)
            .queryParameter(Parameters.optionalParam("page", Schemas.numberSchema()))
            .build()

    override suspend fun handler(context: RoutingContext): Result {
        val parameters = getParameters(context)
        val page = parameters.queryParameter("page")?.long ?: 1

        val sqlConnection = createConnection(databaseManager, context)

        val count = databaseManager.ticketCategoryDao.count(sqlConnection)

        var totalPage = ceil(count.toDouble() / 10).toLong()

        if (totalPage < 1)
            totalPage = 1

        if (page > totalPage || page < 1) {
            return Error(ErrorCode.PAGE_NOT_FOUND)
        }

        val categories = databaseManager.ticketCategoryDao.getByPage(page, sqlConnection)

        val categoriesDataList = mutableListOf<Map<String, Any?>>()

        if (categories.isEmpty()) {
            return getResult(categoriesDataList, count, totalPage)
        }

        val addCategoryToList =
            { category: TicketCategory, count: Long, categoryDataList: MutableList<Map<String, Any?>>, tickets: List<Ticket> ->
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
            }

        val getCategoryData: suspend (TicketCategory) -> Unit = { category ->
            val count = databaseManager.ticketDao.countByCategory(category.id, sqlConnection)

            val tickets = databaseManager.ticketDao.getByCategory(category.id, sqlConnection)

            addCategoryToList(category, count, categoriesDataList, tickets)
        }

        categories.forEach {
            getCategoryData(it)
        }

        return getResult(categoriesDataList, count, totalPage)
    }

    private fun getResult(
        categoryDataList: MutableList<Map<String, Any?>>,
        count: Long,
        totalPage: Long
    ): Result {
        return Successful(
            mutableMapOf<String, Any?>(
                "categories" to categoryDataList,
                "categoryCount" to count,
                "totalPage" to totalPage,
                "host" to "http://"
            )
        )
    }
}