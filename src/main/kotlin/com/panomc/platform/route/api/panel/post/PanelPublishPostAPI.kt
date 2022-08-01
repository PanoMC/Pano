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
import com.panomc.platform.util.TextUtil
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Bodies.json
import io.vertx.json.schema.SchemaParser
import io.vertx.json.schema.common.dsl.Schemas.*

@Endpoint
class PanelPublishPostAPI(
    private val authProvider: AuthProvider,
    private val databaseManager: DatabaseManager,
    setupManager: SetupManager
) : PanelApi(setupManager, authProvider) {
    override val routeType = RouteType.POST

    override val routes = arrayListOf("/api/panel/post")

    override fun getValidationHandler(schemaParser: SchemaParser): ValidationHandler =
        ValidationHandler.builder(schemaParser)
            .body(
                json(
                    objectSchema()
                        .property("title", stringSchema())
                        .property("category", numberSchema())
                        .property("text", stringSchema())
                        .optionalProperty("imageCode", stringSchema())
                )
            )
            .build()

    override suspend fun handler(context: RoutingContext): Result {
        val parameters = getParameters(context)
        val data = parameters.body().jsonObject

        val title = data.getString("title")
        val categoryId = data.getLong("category")
        val text = data.getString("text")
        val imageCode = data.getString("imageCode") ?: ""
        val url = TextUtil.convertStringToUrl(title, 32)

        val userId = authProvider.getUserIdFromRoutingContext(context)

        val sqlConnection = createConnection(databaseManager, context)

        val post = Post(
            title = title,
            categoryId = categoryId,
            writerUserId = userId,
            text = text,
            image = imageCode,
            url = url
        )

        val postId = databaseManager.postDao.insertAndPublish(post, sqlConnection)

        databaseManager.postDao.updatePostUrlByUrl(url, "$url-$postId", sqlConnection)

        return Successful(
            mapOf(
                "id" to postId
            )
        )
    }
}