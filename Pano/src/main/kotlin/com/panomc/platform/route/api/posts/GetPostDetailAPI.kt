package com.panomc.platform.route.api.posts


import com.panomc.platform.i18n.PostI18n
import com.panomc.platform.i18n.ContentI18n
import com.panomc.platform.i18n.ContentTranslations
import com.panomc.platform.util.PostStatus
import com.panomc.platform.util.HtmlSanitizer
import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.Post
import com.panomc.platform.db.model.PostCategory
import com.panomc.platform.error.PostNotFound
import com.panomc.platform.model.*
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import com.panomc.platform.schema.dsl.Parameters
import com.panomc.platform.schema.dsl.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.stringSchema
import com.panomc.platform.util.UsageMode
import com.panomc.platform.schema.EndpointDoc
import com.panomc.platform.schema.CoreSchemas

@Endpoint
class GetPostDetailAPI(
    private val databaseManager: DatabaseManager,
    private val contentTranslations: ContentTranslations
) : Api() {
    override val usageModes = UsageMode.WITH_WEBSITE

    override val paths = listOf(Path("/posts/:url", RouteType.GET))

    override val doc = EndpointDoc(
        summary = "One published post with the posts before and after it by date.",
        tag = "posts",
        response = CoreSchemas.postDetail,
        errors = listOf(PostNotFound::class)
    )

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .pathParameter(Parameters.param("url", stringSchema()))
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        val parameters = getParameters(context)

        val url = parameters.pathParameter("url").string

        val sqlClient = getSqlClient()

        val isPostExists = databaseManager.postDao.existsByUrl(url, sqlClient)

        if (!isPostExists) {
            throw PostNotFound()
        }

        val post = databaseManager.postDao.getByUrl(url, sqlClient) ?: throw PostNotFound()

        // Drafts and trashed posts are only seen in the panel and its preview.
        if (post.status != PostStatus.PUBLISHED) {
            throw PostNotFound()
        }
        var postCategory: PostCategory? = null

        if (post.categoryId != -1L) {
            postCategory = databaseManager.postCategoryDao.getById(post.categoryId, sqlClient)
        }

        val username = if (post.writerUserId == -1L)
            "-"
        else
            databaseManager.userDao.getUsernameFromUserId(post.writerUserId, sqlClient)

        var previousPost: Post? = null
        var nextPost: Post? = null

        val isPreviousPostExists = databaseManager.postDao.isPreviousPostExistsByDate(post.date, sqlClient)

        if (isPreviousPostExists) {
            previousPost = databaseManager.postDao.getPreviousPostByDate(post.date, sqlClient)
        }

        val isNextPostExists = databaseManager.postDao.isNextPostExistsByDate(post.date, sqlClient)

        if (isNextPostExists) {
            nextPost = databaseManager.postDao.getNextPostByDate(post.date, sqlClient)
        }

        val postTranslations = contentTranslations.loadPosts(listOfNotNull(post.id, previousPost?.id, nextPost?.id), sqlClient)
        val categoryTranslations = contentTranslations.loadPostCategories(listOfNotNull(postCategory?.id), sqlClient)
        fun postI18n(id: Long) = ContentI18n.postI18n(id, postTranslations[id])

        return Successful(
            PostI18n.compactMap(mapOf(
                "post" to mapOf(
                    "id" to post.id,
                    "title" to post.title,
                    "i18n" to postI18n(post.id),
                    "category" to
                            if (postCategory == null)
                                mapOf("id" to -1, "title" to "-")
                            else
                                mapOf<String, Any?>(
                                    "title" to postCategory.title,
                                    "url" to postCategory.url,
                                    "i18n" to ContentI18n.postCategoryI18n(postCategory.id, categoryTranslations[postCategory.id])
                                ),
                    "writer" to mapOf(
                        "username" to username
                    ),
                    "text" to HtmlSanitizer.sanitize(post.text),
                    "date" to post.date,
                    "status" to post.status,
                    "thumbnailUrl" to post.thumbnailUrl,
                    "views" to post.views,
                    "url" to post.url
                ),
                "previousPost" to
                        if (previousPost == null)
                            "-"
                        else
                            mapOf<String, Any?>(
                                "id" to previousPost.id,
                                "title" to previousPost.title,
                                "url" to previousPost.url,
                                "i18n" to PostI18n.titleOnly(postI18n(previousPost.id))
                            ),
                "nextPost" to
                        if (nextPost == null)
                            "-"
                        else
                            mapOf<String, Any?>(
                                "id" to nextPost.id,
                                "title" to nextPost.title,
                                "url" to nextPost.url,
                                "i18n" to PostI18n.titleOnly(postI18n(nextPost.id))
                            )
            ))
        )
    }
}