package com.panomc.platform.route.api.panel.ticket.category


import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.panel.permission.ManageTicketsPermission
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.Ticket
import com.panomc.platform.db.model.TicketCategory
import com.panomc.platform.model.*
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import com.panomc.platform.schema.dsl.Parameters
import com.panomc.platform.schema.dsl.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas
import com.panomc.platform.util.UsageMode

@Endpoint
class PanelGetTicketCategoriesAPI(
    private val databaseManager: DatabaseManager,
    private val authProvider: AuthProvider
) : PanelApi() {
    override val usageModes = UsageMode.WITH_WEBSITE

    override val paths = listOf(Path("/ticket/categories", RouteType.GET))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        Paging.params(ValidationHandlerBuilder.create(schemaRepository))
            .queryParameter(Parameters.optionalParam("search", Schemas.stringSchema()))
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        authProvider.requirePermission(ManageTicketsPermission(), context)

        val parameters = getParameters(context)
        val page = Paging.request(context)
        val search = parameters.queryParameter("search")?.string

        val sqlClient = getSqlClient()

        val count = if (search != null)
            databaseManager.ticketCategoryDao.countBySearch(search, sqlClient)
        else
            databaseManager.ticketCategoryDao.count(sqlClient)

        Paging.requireInRange(page, count)

        val categories = if (search != null)
            databaseManager.ticketCategoryDao.getListBySearch(search, page.limit, page.offset, sqlClient)
        else
            databaseManager.ticketCategoryDao.getList(page.limit, page.offset, sqlClient)

        val categoriesDataList = mutableListOf<Map<String, Any?>>()

        if (categories.isEmpty()) {
            return getResult(categoriesDataList, count, page)
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

        return getResult(categoriesDataList, count, page)
    }

    private fun getResult(
        categoryDataList: MutableList<Map<String, Any?>>,
        count: Long,
        page: PageRequest
    ): Result = Successful(payload(categoryDataList, count, page))

    companion object {
        /** The whole response body: `{ items, page }` plus the legacy `host` key. */
        fun payload(
            categoryDataList: List<Map<String, Any?>>,
            count: Long,
            page: PageRequest
        ): Map<String, Any?> = Paging.response(categoryDataList, count, page, mapOf("host" to "http://"))
    }
}