package com.panomc.platform.route.api.panel.ticket.category


import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.PanelPermission
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.Ticket
import com.panomc.platform.db.model.TicketCategory
import com.panomc.platform.error.PageNotFound
import com.panomc.platform.model.*
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Parameters
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaParser
import io.vertx.json.schema.common.dsl.Schemas
import kotlin.math.ceil

@Endpoint
class PanelGetTicketCategoriesAPI(
    private val databaseManager: DatabaseManager,
    private val authProvider: AuthProvider
) : PanelApi() {
    override val paths = listOf(Path("/api/panel/ticket/categories", RouteType.GET))

    override fun getValidationHandler(schemaParser: SchemaParser): ValidationHandler =
        ValidationHandlerBuilder.create(schemaParser)
            .queryParameter(Parameters.optionalParam("page", Schemas.numberSchema()))
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        authProvider.requirePermission(PanelPermission.MANAGE_TICKETS, context)

        val parameters = getParameters(context)
        val page = parameters.queryParameter("page")?.long ?: 1

        val sqlClient = getSqlClient()

        val count = databaseManager.ticketCategoryDao.count(sqlClient)

        var totalPage = ceil(count.toDouble() / 10).toLong()

        if (totalPage < 1)
            totalPage = 1

        if (page > totalPage || page < 1) {
            return PageNotFound()
        }

        val categories = databaseManager.ticketCategoryDao.getByPage(page, sqlClient)

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
            val count = databaseManager.ticketDao.countByCategory(category.id, sqlClient)

            val tickets = databaseManager.ticketDao.getByCategory(category.id, sqlClient)

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