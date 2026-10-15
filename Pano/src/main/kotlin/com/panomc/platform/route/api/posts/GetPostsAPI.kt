package com.panomc.platform.route.api.posts

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.model.Api
import com.panomc.platform.model.Paging
import com.panomc.platform.model.Path
import com.panomc.platform.model.Result
import com.panomc.platform.model.RouteType
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import com.panomc.platform.schema.dsl.Parameters
import com.panomc.platform.schema.dsl.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas
import com.panomc.platform.util.UsageMode
import com.panomc.platform.schema.EndpointDoc
import com.panomc.platform.error.CategoryNotExists
import com.panomc.platform.error.InvalidFields
import com.panomc.platform.error.PageNotFound
import com.panomc.platform.schema.CoreSchemas

@Endpoint
class GetPostsAPI(
    private val getPostsService: GetPostsService
) : Api() {
    override val usageModes = UsageMode.WITH_WEBSITE

    override val paths = listOf(Path("/posts", RouteType.GET))

    override val doc = EndpointDoc(
        summary = "Published posts, newest first, optionally of one category.",
        tag = "posts",
        paginatedItem = CoreSchemas.postSummary,
        errors = listOf(CategoryNotExists::class, InvalidFields::class, PageNotFound::class)
    )

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        Paging.params(ValidationHandlerBuilder.create(schemaRepository))
            .queryParameter(Parameters.optionalParam("categoryUrl", Schemas.stringSchema()))
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        val parameters = getParameters(context)
        val page = Paging.request(context, GetPostsService.DEFAULT_PAGE_SIZE)
        val sqlClient = getSqlClient()

        return getPostsService.handle(parameters, page, sqlClient)
    }
}