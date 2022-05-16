package com.panomc.platform.route.api.panel.post

import com.panomc.platform.ErrorCode
import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.model.*
import com.panomc.platform.util.AuthProvider
import com.panomc.platform.util.SetupManager
import io.vertx.ext.web.RoutingContext

@Endpoint
class EditPostPageInitAPI(
    private val databaseManager: DatabaseManager,
    setupManager: SetupManager,
    authProvider: AuthProvider
) : PanelApi(setupManager, authProvider) {
    override val routeType = RouteType.POST

    override val routes = arrayListOf("/api/panel/initPage/editPost")

    override suspend fun handler(context: RoutingContext): Result {
        val data = context.bodyAsJson

        val id = data.getInteger("id")

        val sqlConnection = createConnection(databaseManager, context)

        val exists = databaseManager.postDao.isExistsByID(id, sqlConnection)

        if (!exists) {
            throw Error(ErrorCode.POST_NOT_FOUND)
        }

        val post = databaseManager.postDao.getByID(id, sqlConnection) ?: throw Error(ErrorCode.UNKNOWN)

        return Successful(
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
    }
}