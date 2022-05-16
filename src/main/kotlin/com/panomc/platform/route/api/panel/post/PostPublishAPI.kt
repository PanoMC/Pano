package com.panomc.platform.route.api.panel.post

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.Post
import com.panomc.platform.model.PanelApi
import com.panomc.platform.model.Result
import com.panomc.platform.model.RouteType
import com.panomc.platform.model.Successful
import com.panomc.platform.util.AuthProvider
import com.panomc.platform.util.SetupManager
import io.vertx.ext.web.RoutingContext

@Endpoint
class PostPublishAPI(
    private val authProvider: AuthProvider,
    private val databaseManager: DatabaseManager,
    setupManager: SetupManager
) : PanelApi(setupManager, authProvider) {
    override val routeType = RouteType.POST

    override val routes = arrayListOf("/api/panel/post/publish")

    override suspend fun handler(context: RoutingContext): Result {
        val data = context.bodyAsJson
        val id = data.getValue("id").toString().toInt()
        val title = data.getString("title")
        val categoryID = data.getValue("category").toString().toInt()
        val text = data.getString("text")
        val imageCode = data.getString("imageCode") ?: ""

        val userID = authProvider.getUserIDFromRoutingContext(context)

        val sqlConnection = createConnection(databaseManager, context)

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
            val postId = databaseManager.postDao.insertAndPublish(post, sqlConnection)

            return Successful(
                mapOf(
                    "id" to postId
                )
            )
        }

        databaseManager.postDao.updateAndPublish(userID, post, sqlConnection)

        return Successful()
    }
}