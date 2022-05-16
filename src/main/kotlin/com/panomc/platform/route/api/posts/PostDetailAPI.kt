package com.panomc.platform.route.api.posts

import com.panomc.platform.ErrorCode
import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.Post
import com.panomc.platform.db.model.PostCategory
import com.panomc.platform.model.*
import io.vertx.ext.web.RoutingContext

@Endpoint
class PostDetailAPI(
    private val databaseManager: DatabaseManager
) : Api() {
    override val routeType = RouteType.POST

    override val routes = arrayListOf("/api/posts/detail")

    override suspend fun handler(context: RoutingContext): Result {
        val data = context.bodyAsJson
        val id = data.getInteger("id")

        val sqlConnection = createConnection(databaseManager, context)

        val isPostExists = databaseManager.postDao.isExistsByID(id, sqlConnection)

        if (!isPostExists) {
            throw Error(ErrorCode.POST_NOT_FOUND)
        }

        databaseManager.postDao.increaseViewByOne(id, sqlConnection)

        val post = databaseManager.postDao.getByID(id, sqlConnection) ?: throw Error(ErrorCode.UNKNOWN)
        var postCategory: PostCategory? = null

        if (post.categoryId != -1) {
            postCategory = databaseManager.postCategoryDao.getByID(post.categoryId, sqlConnection)
        }

        val username = databaseManager.userDao.getUsernameFromUserID(post.writerUserID, sqlConnection)

        var previousPost: Post? = null
        var nextPost: Post? = null

        val isPreviousPostExists = databaseManager.postDao.isPreviousPostExistsByDate(post.date, sqlConnection)

        if (isPreviousPostExists) {
            previousPost = databaseManager.postDao.getPreviousPostByDate(post.date, sqlConnection)
        }

        val isNextPostExists = databaseManager.postDao.isNextPostExistsByDate(post.date, sqlConnection)

        if (isNextPostExists) {
            nextPost = databaseManager.postDao.getNextPostByDate(post.date, sqlConnection)
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
                    "status" to post.status,
                    "image" to post.image,
                    "views" to post.views
                ),
                "previousPost" to
                        if (previousPost == null)
                            "-"
                        else
                            mapOf<String, Any?>(
                                "id" to previousPost.id,
                                "title" to previousPost.title
                            ),
                "nextPost" to
                        if (nextPost == null)
                            "-"
                        else
                            mapOf<String, Any?>(
                                "id" to nextPost.id,
                                "title" to nextPost.title
                            )
            )
        )
    }
}