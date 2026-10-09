package com.panomc.platform.route.api.ticket

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.model.*
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import com.panomc.platform.schema.dsl.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import com.panomc.platform.util.UsageMode
import com.panomc.platform.schema.EndpointDoc
import com.panomc.platform.error.InvalidFields
import com.panomc.platform.error.PageNotFound
import com.panomc.platform.schema.CoreSchemas

@Endpoint
class GetTicketCategoriesAPI(
    val databaseManager: DatabaseManager
) : LoggedInApi() {
    override val usageModes = UsageMode.WITH_WEBSITE

    override val paths = listOf(Path("/ticket-categories", RouteType.GET))

    override val doc = EndpointDoc(
        summary = "The ticket categories a new ticket can be filed under.",
        tag = "tickets",
        paginatedItem = CoreSchemas.ticketCategory,
        errors = listOf(InvalidFields::class, PageNotFound::class)
    )

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        Paging.params(ValidationHandlerBuilder.create(schemaRepository)).build()

    override suspend fun handle(context: RoutingContext): Result {
        val page = Paging.request(context, DEFAULT_PAGE_SIZE)

        val sqlClient = getSqlClient()

        val count = databaseManager.ticketCategoryDao.count(sqlClient)

        Paging.requireInRange(page, count)

        val categories = databaseManager.ticketCategoryDao.getList(page.limit, page.offset, sqlClient)

        return Successful(Paging.response(categories, count, page))
    }

    companion object {
        /**
         * The category list feeds the "new ticket" form, so one default page holds the most a client may ask
         * for (it used to be unpaged).
         */
        const val DEFAULT_PAGE_SIZE = Paging.MAX_SIZE
    }
}
