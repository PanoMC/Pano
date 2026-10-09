package com.panomc.platform.route.api.ticket

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.model.LoggedInApi
import com.panomc.platform.model.Paging
import com.panomc.platform.model.Path
import com.panomc.platform.model.Result
import com.panomc.platform.model.RouteType
import com.panomc.platform.util.TicketPageType
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import com.panomc.platform.schema.dsl.Parameters.optionalParam
import com.panomc.platform.schema.dsl.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas
import io.vertx.json.schema.common.dsl.Schemas.arraySchema
import com.panomc.platform.util.UsageMode
import com.panomc.platform.schema.EndpointDoc
import com.panomc.platform.error.NotExists
import com.panomc.platform.error.InvalidFields
import com.panomc.platform.error.PageNotFound
import com.panomc.platform.schema.CoreSchemas

@Endpoint
class GetTicketsAPI(
    private val getTicketsService: GetTicketsService
) : LoggedInApi() {
    override val usageModes = UsageMode.WITH_WEBSITE

    override val paths = listOf(Path("/tickets", RouteType.GET))

    override val doc = EndpointDoc(
        summary = "The signed-in user's tickets, filtered by page type or category.",
        tag = "tickets",
        paginatedItem = CoreSchemas.ticket,
        errors = listOf(NotExists::class, InvalidFields::class, PageNotFound::class)
    )

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .queryParameter(
                optionalParam(
                    "pageType",
                    arraySchema()
                        .items(
                            Schemas.enumSchema(
                                *TicketPageType.entries
                                    .map { it.name }
                                    .toTypedArray()
                            )
                        )
                )
            )
            .queryParameter(optionalParam("categoryUrl", Schemas.stringSchema()))
            .let { Paging.params(it) }
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        val sqlClient = getSqlClient()
        val parameters = getParameters(context)
        val page = Paging.request(context, GetTicketsService.DEFAULT_PAGE_SIZE)

        return getTicketsService.handle(context, sqlClient, parameters, page)
    }
}