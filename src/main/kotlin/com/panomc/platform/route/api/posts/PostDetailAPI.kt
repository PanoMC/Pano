package com.panomc.platform.route.api.posts

import com.panomc.platform.ErrorCode
import com.panomc.platform.db.model.Post
import com.panomc.platform.db.model.PostCategory
import com.panomc.platform.model.*
import io.vertx.core.AsyncResult
import io.vertx.ext.web.RoutingContext
import io.vertx.sqlclient.SqlConnection

class PostDetailAPI : Api() {
    override val routeType = RouteType.POST

    override val routes = arrayListOf("/api/posts/detail")

    override fun handler(context: RoutingContext, handler: (result: Result) -> Unit) {
        val data = context.bodyAsJson
        val id = data.getInteger("id")

        databaseManager.createConnection(
            (this::createConnectionHandler)(
                handler,
                id
            )
        )
    }

    private fun createConnectionHandler(
        handler: (result: Result) -> Unit,
        id: Int
    ) = handler@{ sqlConnection: SqlConnection?, _: AsyncResult<SqlConnection> ->
        if (sqlConnection == null) {
            handler.invoke(Error(ErrorCode.CANT_CONNECT_DATABASE))

            return@handler
        }

        databaseManager.getDatabase().postDao.isExistsByID(
            id,
            sqlConnection,
            (this::isExistsByIDHandler)(handler, sqlConnection, id)
        )
    }

    private fun isExistsByIDHandler(
        handler: (result: Result) -> Unit,
        sqlConnection: SqlConnection,
        id: Int
    ) = handler@{ exists: Boolean?, _: AsyncResult<*> ->
        if (exists == null) {
            databaseManager.closeConnection(sqlConnection) {
                handler.invoke(Error(ErrorCode.UNKNOWN))
            }

            return@handler
        }

        if (!exists) {
            databaseManager.closeConnection(sqlConnection) {
                handler.invoke(Error(ErrorCode.POST_NOT_FOUND))
            }

            return@handler
        }

        databaseManager.getDatabase().postDao.increaseViewByOne(
            id,
            sqlConnection,
            (this::increaseViewByOneHandler)(handler, sqlConnection, id)
        )
    }

    private fun increaseViewByOneHandler(
        handler: (result: Result) -> Unit,
        sqlConnection: SqlConnection,
        id: Int
    ) = handler@{ result: Result?, _: AsyncResult<*> ->
        if (result == null) {
            databaseManager.closeConnection(sqlConnection) {
                handler.invoke(Error(ErrorCode.UNKNOWN))
            }

            return@handler
        }

        databaseManager.getDatabase().postDao.getByID(
            id,
            sqlConnection,
            (this::getByIDHandler)(handler, sqlConnection)
        )
    }

    private fun getByIDHandler(
        handler: (result: Result) -> Unit,
        sqlConnection: SqlConnection,
    ) = handler@{ post: Post?, _: AsyncResult<*> ->
        if (post == null) {
            databaseManager.closeConnection(sqlConnection) {
                handler.invoke(Error(ErrorCode.UNKNOWN))
            }

            return@handler
        }

        if (post.categoryId == -1) {
            databaseManager.getDatabase().userDao.getUsernameFromUserID(
                post.writerUserID,
                sqlConnection,
                (this::getUsernameFromUserIDHandler)(handler, sqlConnection, post, null)
            )

            return@handler
        }

        databaseManager.getDatabase().postCategoryDao.getByID(
            post.categoryId,
            sqlConnection,
            (this::getPostCategoryByIDHandler)(handler, sqlConnection, post)
        )
    }

    private fun getPostCategoryByIDHandler(
        handler: (result: Result) -> Unit,
        sqlConnection: SqlConnection,
        post: Post
    ) = handler@{ category: PostCategory?, _: AsyncResult<*> ->
        if (category == null) {
            databaseManager.closeConnection(sqlConnection) {
                handler.invoke(Error(ErrorCode.UNKNOWN))
            }

            return@handler
        }

        databaseManager.getDatabase().userDao.getUsernameFromUserID(
            post.writerUserID,
            sqlConnection,
            (this::getUsernameFromUserIDHandler)(handler, sqlConnection, post, category)
        )
    }

    private fun getUsernameFromUserIDHandler(
        handler: (result: Result) -> Unit,
        sqlConnection: SqlConnection,
        post: Post,
        category: PostCategory?
    ) = handler@{ username: String?, _: AsyncResult<*> ->
        if (username == null) {
            databaseManager.closeConnection(sqlConnection) {
                handler.invoke(Error(ErrorCode.UNKNOWN))
            }

            return@handler
        }

        databaseManager.getDatabase().postDao.isPreviousPostExistsByDate(
            post.date,
            sqlConnection,
            (this::isPreviousPostExistsByDateHandler)(handler, sqlConnection, post, category, username)
        )
    }

    private fun isPreviousPostExistsByDateHandler(
        handler: (result: Result) -> Unit,
        sqlConnection: SqlConnection,
        post: Post,
        category: PostCategory?,
        username: String
    ) = handler@{ exists: Boolean?, _: AsyncResult<*> ->
        if (exists == null) {
            databaseManager.closeConnection(sqlConnection) {
                handler.invoke(Error(ErrorCode.UNKNOWN))
            }

            return@handler
        }

        if (!exists) {
            databaseManager.getDatabase().postDao.isNextPostExistsByDate(
                post.date,
                sqlConnection,
                (this::isNextPostExistsByDateHandler)(handler, sqlConnection, post, category, username, null)
            )

            return@handler
        }

        databaseManager.getDatabase().postDao.getPreviousPostByDate(
            post.date,
            sqlConnection,
            (this::getPreviousPostByDateHandler)(handler, sqlConnection, post, category, username)
        )
    }

    private fun getPreviousPostByDateHandler(
        handler: (result: Result) -> Unit,
        sqlConnection: SqlConnection,
        post: Post,
        category: PostCategory?,
        username: String
    ) = handler@{ previousPost: Post?, _: AsyncResult<*> ->
        if (previousPost == null) {
            databaseManager.closeConnection(sqlConnection) {
                handler.invoke(Error(ErrorCode.UNKNOWN))
            }

            return@handler
        }

        databaseManager.getDatabase().postDao.isNextPostExistsByDate(
            post.date,
            sqlConnection,
            (this::isNextPostExistsByDateHandler)(handler, sqlConnection, post, category, username, previousPost)
        )
    }

    private fun isNextPostExistsByDateHandler(
        handler: (result: Result) -> Unit,
        sqlConnection: SqlConnection,
        post: Post,
        category: PostCategory?,
        username: String,
        previousPost: Post?
    ) = handler@{ exists: Boolean?, _: AsyncResult<*> ->
        if (exists == null) {
            databaseManager.closeConnection(sqlConnection) {
                handler.invoke(Error(ErrorCode.UNKNOWN))
            }

            return@handler
        }

        if (!exists) {
            sendResultHandler(handler, sqlConnection, post, category, username, previousPost, null)

            return@handler
        }

        databaseManager.getDatabase().postDao.getNextPostByDate(
            post.date,
            sqlConnection,
            (this::getNextPostByDateHandler)(handler, sqlConnection, post, category, username, previousPost)
        )
    }

    private fun getNextPostByDateHandler(
        handler: (result: Result) -> Unit,
        sqlConnection: SqlConnection,
        post: Post,
        category: PostCategory?,
        username: String,
        previousPost: Post?
    ) = handler@{ nextPost: Post?, _: AsyncResult<*> ->
        if (nextPost == null) {
            databaseManager.closeConnection(sqlConnection) {
                handler.invoke(Error(ErrorCode.UNKNOWN))
            }

            return@handler
        }

        sendResultHandler(handler, sqlConnection, post, category, username, previousPost, nextPost)
    }

    private fun sendResultHandler(
        handler: (result: Result) -> Unit,
        sqlConnection: SqlConnection,
        post: Post,
        category: PostCategory?,
        username: String,
        previousPost: Post?,
        nextPost: Post?
    ) {
        databaseManager.closeConnection(sqlConnection) {
            handler.invoke(
                Successful(
                    mapOf(
                        "post" to mapOf(
                            "id" to post.id,
                            "title" to post.title,
                            "category" to
                                    if (category == null)
                                        "-"
                                    else
                                        mapOf<String, Any?>(
                                            "title" to category.title,
                                            "url" to category.url
                                        ),
                            "writer" to mapOf(
                                "username" to username
                            ),
                            "text" to post.post,
                            "date" to post.date,
                            "status" to post.status,
                            "image" to post.image,
                            "views" to post.views
                        ),
                        "previous_post" to
                                if (previousPost == null)
                                    "-"
                                else
                                    mapOf<String, Any?>(
                                        "id" to previousPost.id,
                                        "title" to previousPost.title
                                    ),
                        "next_post" to
                                if (nextPost == null)
                                    "-"
                                else
                                    mapOf<String, Any?>(
                                        "id" to nextPost.id,
                                        "title" to nextPost.title
                                    )
                    )
                )
            )
        }
    }
}