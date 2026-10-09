package com.panomc.platform.route.api.posts

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.Post
import com.panomc.platform.db.model.PostCategory
import com.panomc.platform.error.NoPermission
import com.panomc.platform.error.PostNotFound
import com.panomc.platform.model.*
import com.panomc.platform.util.UsageMode
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import com.panomc.platform.schema.dsl.Parameters.param
import com.panomc.platform.schema.dsl.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.numberSchema
import com.panomc.platform.schema.EndpointDoc
import com.panomc.platform.schema.CoreSchemas

/**
 * `GET /api/v1/posts/previews/:id` -- a post by id, draft or not, for the person writing it (doc 04
 * §8). Replaces the panel's `/api/v1/panel/posts/:id/preview`.
 *
 * The answer has the shape of `GET /api/v1/posts/:url`, so a theme renders the preview with the same
 * view it renders a published post with. The caller must be logged in and must be allowed into
 * the panel, which is exactly what the panel endpoint demanded.
 */
@Endpoint
class GetPostPreviewAPI(
    private val databaseManager: DatabaseManager,
    private val authProvider: AuthProvider
) : LoggedInApi() {
    override val usageModes = UsageMode.WITH_WEBSITE

    override val paths = listOf(Path("/posts/previews/:id", RouteType.GET))

    override val doc = EndpointDoc(
        summary = "A post by id, draft or not, for a person who may use the panel; same body as a published post.",
        tag = "posts",
        response = CoreSchemas.postDetail,
        errors = listOf(PostNotFound::class)
    )

    // The panel endpoint this replaces was never gated by maintenance mode, and this one still
    // demands the panel permission itself.
    override val maintenanceAccess = MaintenanceAccess.ALWAYS

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .pathParameter(param("id", numberSchema()))
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        requirePanelAccess(authProvider.hasAccessPanel(context))

        val id = getParameters(context).pathParameter("id").long

        val sqlClient = getSqlClient()

        val post = requirePost(databaseManager.postDao.getById(id, sqlClient))

        val category = if (post.categoryId != -1L) {
            databaseManager.postCategoryDao.getById(post.categoryId, sqlClient)
        } else null

        val username = if (post.writerUserId == -1L) {
            null
        } else {
            databaseManager.userDao.getUsernameFromUserId(post.writerUserId, sqlClient)
        }

        val previous = if (databaseManager.postDao.isPreviousPostExistsByDate(post.date, sqlClient)) {
            databaseManager.postDao.getPreviousPostByDate(post.date, sqlClient)
        } else null

        val next = if (databaseManager.postDao.isNextPostExistsByDate(post.date, sqlClient)) {
            databaseManager.postDao.getNextPostByDate(post.date, sqlClient)
        } else null

        return Successful(body(post, category, username, previous, next))
    }

    companion object {
        fun requirePanelAccess(hasAccess: Boolean) {
            if (!hasAccess) {
                throw NoPermission()
            }
        }

        fun requirePost(post: Post?): Post = post ?: throw PostNotFound()

        /** The body of `GET /api/v1/posts/:url`, built from already loaded rows. */
        fun body(
            post: Post,
            category: PostCategory?,
            writerUsername: String?,
            previous: Post?,
            next: Post?
        ): Map<String, Any?> = mapOf(
            "post" to mapOf(
                "id" to post.id,
                "title" to post.title,
                "category" to
                        if (category == null)
                            mapOf("id" to -1, "title" to "-")
                        else
                            mapOf<String, Any?>("title" to category.title, "url" to category.url),
                "writer" to mapOf("username" to (writerUsername ?: "-")),
                "text" to post.text,
                "date" to post.date,
                "status" to post.status,
                "thumbnailUrl" to post.thumbnailUrl,
                "views" to post.views,
                "url" to post.url
            ),
            "previousPost" to neighbour(previous),
            "nextPost" to neighbour(next)
        )

        private fun neighbour(post: Post?): Any =
            if (post == null) "-" else mapOf<String, Any?>("id" to post.id, "title" to post.title, "url" to post.url)
    }
}
