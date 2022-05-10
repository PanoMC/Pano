package com.panomc.platform.route.api.panel.post

import com.panomc.platform.ErrorCode
import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.Post
import com.panomc.platform.model.*
import com.panomc.platform.util.AuthProvider
import com.panomc.platform.util.SetupManager
import io.vertx.core.AsyncResult
import io.vertx.ext.web.RoutingContext
import io.vertx.sqlclient.SqlConnection

@Endpoint
class EditPostPageInitAPI(
    private val databaseManager: DatabaseManager,
    setupManager: SetupManager,
    authProvider: AuthProvider
) : PanelApi(setupManager, authProvider) {
    override val routeType = RouteType.POST

    override val routes = arrayListOf("/api/panel/initPage/editPost")

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
    ) =
        handler@{ sqlConnection: SqlConnection?, _: AsyncResult<SqlConnection> ->
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
        databaseManager.closeConnection(sqlConnection) {
            if (post == null) {
                handler.invoke(Error(ErrorCode.UNKNOWN))

                return@closeConnection
            }

            handler.invoke(
                Successful(
                    mapOf(
                        "post" to mapOf(
                            "id" to post.id,
                            "title" to post.title,
                            "category" to post.categoryId,
                            "writerUserId" to post.writerUserID,
                            "text" to post.text,
                            "date" to post.date,
                            "status" to post.status,
                            "image" to post.image,
                            "views" to post.views
                        )
                    )
                )
            )
        }
    }
}