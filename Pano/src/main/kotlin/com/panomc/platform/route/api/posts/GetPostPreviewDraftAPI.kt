package com.panomc.platform.route.api.posts

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.Post
import com.panomc.platform.error.PostNotFound
import com.panomc.platform.model.*
import com.panomc.platform.post.PostPreviewDrafts
import com.panomc.platform.schema.EndpointDoc
import com.panomc.platform.schema.CoreSchemas
import com.panomc.platform.schema.dsl.Parameters.param
import com.panomc.platform.schema.dsl.ValidationHandlerBuilder
import com.panomc.platform.util.PostStatus
import com.panomc.platform.util.UsageMode
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.stringSchema

/**
 * `GET /api/v1/posts/previews/drafts/:token` -- the post editor's unsaved content (see
 * `PanelCreatePostPreviewAPI`), in the shape of `GET /api/v1/posts/:url`, so a theme renders it with the view
 * it renders a published post with.
 *
 * Only the author who made the draft reads it, and only while it is fresh. What the draft does not carry (the
 * date, the view count, the address) comes from the saved post it edits, or is empty for a post that does not
 * exist yet.
 */
@Endpoint
class GetPostPreviewDraftAPI(
    private val databaseManager: DatabaseManager,
    private val authProvider: AuthProvider,
    private val postPreviewDrafts: PostPreviewDrafts
) : LoggedInApi() {
    override val usageModes = UsageMode.WITH_WEBSITE

    override val paths = listOf(Path("/posts/previews/drafts/:token", RouteType.GET))

    override val doc = EndpointDoc(
        summary = "The post editor's unsaved content by its preview token, for the person who made it; same body as a published post.",
        tag = "posts",
        response = CoreSchemas.postDetail,
        errors = listOf(PostNotFound::class)
    )

    // Like the saved-post preview: never gated by maintenance mode, and it demands panel access itself.
    override val maintenanceAccess = MaintenanceAccess.ALWAYS

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .pathParameter(param("token", stringSchema()))
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        GetPostPreviewAPI.requirePanelAccess(authProvider.hasAccessPanel(context))

        val userId = authProvider.getUserIdFromRoutingContext(context)
        val draft = postPreviewDrafts.get(getParameters(context).pathParameter("token").string)
            ?.takeIf { it.userId == userId }
            ?: throw PostNotFound()

        val sqlClient = getSqlClient()
        val saved = draft.postId?.let { databaseManager.postDao.getById(it, sqlClient) }

        val post = Post(
            id = saved?.id ?: -1,
            title = draft.title,
            categoryId = draft.categoryId,
            writerUserId = saved?.writerUserId ?: userId,
            text = draft.text,
            date = saved?.date ?: System.currentTimeMillis(),
            status = saved?.status ?: PostStatus.DRAFT,
            thumbnailUrl = draft.thumbnailUrl,
            views = saved?.views ?: 0,
            url = saved?.url.orEmpty()
        )

        val category = if (post.categoryId != -1L) {
            databaseManager.postCategoryDao.getById(post.categoryId, sqlClient)
        } else null

        val username = if (post.writerUserId == -1L) {
            null
        } else {
            databaseManager.userDao.getUsernameFromUserId(post.writerUserId, sqlClient)
        }

        // No neighbours: an unsaved post has no place in the list yet.
        return Successful(GetPostPreviewAPI.body(post, category, username, null, null))
    }
}
