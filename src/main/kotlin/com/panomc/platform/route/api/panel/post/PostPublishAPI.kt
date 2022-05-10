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
class PostPublishAPI(
    private val authProvider: AuthProvider,
    private val databaseManager: DatabaseManager,
    setupManager: SetupManager
) : PanelApi(setupManager, authProvider) {
    override val routeType = RouteType.POST

    override val routes = arrayListOf("/api/panel/post/publish")

    override fun handler(context: RoutingContext, handler: (result: Result) -> Unit) {
        val data = context.bodyAsJson
        val id = data.getValue("id").toString().toInt()
        val title = data.getString("title")
        val categoryID = data.getValue("category").toString().toInt()
        val text = data.getString("text")
        val imageCode = data.getString("imageCode") ?: ""

        val userID = authProvider.getUserIDFromRoutingContext(context)

        databaseManager.createConnection(
            (this::createConnectionHandler)(
                handler,
                id,
                title,
                categoryID,
                text,
                imageCode,
                userID
            )
        )
    }

    private fun createConnectionHandler(
        handler: (result: Result) -> Unit,
        id: Int,
        title: String,
        categoryID: Int,
        text: String,
        imageCode: String,
        userID: Int
    ) = handler@{ sqlConnection: SqlConnection?, _: AsyncResult<SqlConnection> ->
        if (sqlConnection == null) {
            handler.invoke(Error(ErrorCode.CANT_CONNECT_DATABASE))

            return@handler
        }

        updateOrInsert(handler, sqlConnection, id, title, categoryID, text, imageCode, userID)
    }

    private fun updateOrInsert(
        handler: (result: Result) -> Unit,
        sqlConnection: SqlConnection,
        id: Int,
        title: String,
        categoryID: Int,
        text: String,
        imageCode: String,
        userID: Int
    ) {
        val post = Post(
            id,
            title,
            categoryID,
            userID,
            text,
            System.currentTimeMillis(),
            System.currentTimeMillis(),
            1,
            imageCode,
            0
        )

        if (id == -1) {
            databaseManager.postDao.insertAndPublish(
                post,
                sqlConnection,
                (this::insertAndPublishHandler)(handler, sqlConnection)
            )

            return
        }

        databaseManager.postDao.updateAndPublish(
            userID,
            post,
            sqlConnection,
            (this::updateAndPublishHandler)(handler, sqlConnection)
        )
    }

    private fun insertAndPublishHandler(
        handler: (result: Result) -> Unit,
        sqlConnection: SqlConnection
    ) = handler@{ postID: Long?, _: AsyncResult<*> ->
        databaseManager.closeConnection(sqlConnection) {
            if (postID == null) {
                handler.invoke(Error(ErrorCode.UNKNOWN))

                return@closeConnection
            }

            handler.invoke(
                Successful(
                    mapOf(
                        "id" to postID
                    )
                )
            )
        }
    }

    private fun updateAndPublishHandler(
        handler: (result: Result) -> Unit,
        sqlConnection: SqlConnection
    ) = handler@{ result: Result?, _: AsyncResult<*> ->
        databaseManager.closeConnection(sqlConnection) {
            if (result == null) {
                handler.invoke(Error(ErrorCode.UNKNOWN))

                return@closeConnection
            }

            handler.invoke(Successful())
        }
    }
}