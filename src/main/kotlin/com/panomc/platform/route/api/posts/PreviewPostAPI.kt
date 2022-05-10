package com.panomc.platform.route.api.posts

import com.panomc.platform.ErrorCode
import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.Post
import com.panomc.platform.db.model.PostCategory
import com.panomc.platform.model.*
import com.panomc.platform.util.AuthProvider
import com.panomc.platform.util.SetupManager
import io.vertx.core.AsyncResult
import io.vertx.ext.web.RoutingContext
import io.vertx.sqlclient.SqlConnection

@Endpoint
class PreviewPostAPI(
    private val databaseManager: DatabaseManager,
    setupManager: SetupManager,
    authProvider: AuthProvider
) : PanelApi(setupManager, authProvider) {
    override val routeType = RouteType.POST

    override val routes = arrayListOf("/api/panel/post/preview")

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

        databaseManager.postDao.isExistsByID(
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

        databaseManager.postDao.getByID(
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
            databaseManager.userDao.getUsernameFromUserID(
                post.writerUserID,
                sqlConnection,
                (this::getUsernameFromUserIDHandler)(handler, sqlConnection, post, null)
            )

            return@handler
        }

        databaseManager.postCategoryDao.getByID(
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

        databaseManager.userDao.getUsernameFromUserID(
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

        sendResultHandler(handler, sqlConnection, post, category, username)
    }

    private fun sendResultHandler(
        handler: (result: Result) -> Unit,
        sqlConnection: SqlConnection,
        post: Post,
        category: PostCategory?,
        username: String,
    ) {
        databaseManager.closeConnection(sqlConnection) {
            handler.invoke(
                Successful(
                    mapOf(
                        "id" to post.id,
                        "title" to post.title,
                        "category" to
                                if (category == null)
                                    mapOf("id" to -1, "title" to "-")
                                else
                                    mapOf<String, Any?>(
                                        "title" to category.title,
                                        "url" to category.url
                                    ),
                        "writer" to mapOf(
                            "username" to username
                        ),
                        "text" to post.text,
                        "date" to post.date,
                        "status" to post.status,
                        "image" to post.image,
                        "views" to post.views
                    )
                )
            )
        }
    }
}