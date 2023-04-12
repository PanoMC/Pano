package com.panomc.platform.route.api.posts

import com.panomc.platform.ErrorCode
import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.Post
import com.panomc.platform.db.model.PostCategory
import com.panomc.platform.model.*
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Parameters
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaParser
import io.vertx.json.schema.common.dsl.Schemas.stringSchema

@Endpoint
class GetPostDetailAPI(
    private val databaseManager: DatabaseManager
) : Api() {
    override val paths = listOf(Path("/api/posts/:url", RouteType.POST))

    override fun getValidationHandler(schemaParser: SchemaParser): ValidationHandler =
        ValidationHandlerBuilder.create(schemaParser)
            .pathParameter(Parameters.param("url", stringSchema()))
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        val parameters = getParameters(context)

        val url = parameters.pathParameter("url").string

        val sqlClient = getSqlClient()

        val isPostExists = databaseManager.postDao.existsByUrl(url, sqlClient)

        if (!isPostExists) {
            throw Error(ErrorCode.POST_NOT_FOUND)
        }

        databaseManager.postDao.increaseViewByOne(url, sqlClient)

        val post = databaseManager.postDao.getByUrl(url, sqlClient)!!
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

        return Successful(
            mapOf(
                "post" to mapOf(
                    "id" to post.id,
                    "title" to post.title,
                    "category" to
                            if (postCategory == null)
                                mapOf("id" to -1, "title" to "-")
                            else
                                mapOf<String, Any?>(
                                    "title" to postCategory.title,
                                    "url" to postCategory.url
                                ),
                    "writer" to mapOf(
                        "username" to username
                    ),
                    "text" to post.text,
                    "date" to post.date,
                    "status" to post.status.value,
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
                                "url" to previousPost.url
                            ),
                "nextPost" to
                        if (nextPost == null)
                            "-"
                        else
                            mapOf<String, Any?>(
                                "id" to nextPost.id,
                                "title" to nextPost.title,
                                "url" to nextPost.url
                            )
            )
        )
    }
}