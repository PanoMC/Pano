package com.panomc.platform.route.api.panel.post

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.panel.permission.ManagePostsPermission
import com.panomc.platform.error.BadRequest
import com.panomc.platform.model.*
import com.panomc.platform.post.PostPreviewDrafts
import com.panomc.platform.schema.dsl.Bodies.json
import com.panomc.platform.schema.dsl.ValidationHandlerBuilder
import com.panomc.platform.util.UsageMode
import io.vertx.core.Handler
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.handler.BodyHandler
import io.vertx.ext.web.validation.RequestPredicate
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.*

/**
 * `POST /api/v1/panel/posts/previews` -- hands the post editor's current, unsaved content to the preview and
 * answers `{ token }`. The theme's preview page reads it back with `GET /api/v1/posts/previews/drafts/:token`.
 *
 * Nothing is saved: this is what lets an author look at a change before deciding to keep it.
 */
@Endpoint
class PanelCreatePostPreviewAPI(
    private val authProvider: AuthProvider,
    private val postPreviewDrafts: PostPreviewDrafts
) : PanelApi() {
    override val usageModes = UsageMode.WITH_WEBSITE

    override val paths = listOf(Path("/posts/previews", RouteType.POST))

    // An inline cover image rides along as a `data:` address, so the default limit is too small.
    override fun bodyHandler(): Handler<RoutingContext> =
        BodyHandler.create().setBodyLimit(BODY_LIMIT_BYTES)

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .body(
                json(
                    objectSchema()
                        .optionalProperty("id", numberSchema())
                        .requiredProperty("title", stringSchema())
                        .requiredProperty("text", stringSchema())
                        .optionalProperty("categoryId", numberSchema())
                        .optionalProperty("thumbnailUrl", stringSchema())
                )
            )
            .predicate(RequestPredicate.BODY_REQUIRED)
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        authProvider.requirePermission(ManagePostsPermission(), context)

        val data = getParameters(context).body().jsonObject
        val title = data.getString("title").orEmpty()
        val text = data.getString("text").orEmpty()
        val thumbnailUrl = data.getString("thumbnailUrl").orEmpty()

        if (title.length + text.length + thumbnailUrl.length > PostPreviewDrafts.MAX_CHARS) {
            throw BadRequest()
        }

        val token = postPreviewDrafts.put(
            PostPreviewDrafts.Draft(
                userId = authProvider.getUserIdFromRoutingContext(context),
                postId = data.getLong("id")?.takeIf { it > 0 },
                title = title,
                text = text,
                categoryId = data.getLong("categoryId") ?: -1L,
                thumbnailUrl = thumbnailUrl,
                createdAt = System.currentTimeMillis()
            )
        )

        return Successful(mapOf("token" to token))
    }

    companion object {
        private const val BODY_LIMIT_BYTES = 12L * 1024 * 1024
    }
}
