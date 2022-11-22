package com.panomc.platform.route.api.panel.post

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.config.ConfigManager
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.Post
import com.panomc.platform.model.PanelApi
import com.panomc.platform.model.Result
import com.panomc.platform.model.RouteType
import com.panomc.platform.model.Successful
import com.panomc.platform.util.*
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Bodies.multipartFormData
import io.vertx.json.schema.SchemaParser
import io.vertx.json.schema.common.dsl.Schemas.*

@Endpoint
class PanelPublishPostAPI(
    private val authProvider: AuthProvider,
    private val databaseManager: DatabaseManager,
    setupManager: SetupManager,
    private val configManager: ConfigManager
) : PanelApi(setupManager, authProvider) {
    override val routeType = RouteType.POST

    override val routes = arrayListOf("/api/panel/post")

    override fun getValidationHandler(schemaParser: SchemaParser): ValidationHandler =
        ValidationHandler.builder(schemaParser)
            .body(
                multipartFormData(
                    objectSchema()
                        .property("title", stringSchema())
                        .property("category", numberSchema())
                        .property("text", stringSchema())
                )
            )
            .build()

    override suspend fun handler(context: RoutingContext): Result {
        val parameters = getParameters(context)
        val data = parameters.body().jsonObject

        val fileUploads = context.fileUploads()

        val title = data.getString("title")
        val categoryId = data.getLong("category")
        val text = data.getString("text")
        val url = TextUtil.convertStringToUrl(title, 32)

        var thumbnailUrl = ""

        val userId = authProvider.getUserIdFromRoutingContext(context)

        val sqlConnection = createConnection(databaseManager, context)

        if (fileUploads.size > 0) {
            val savedFiles = FileUploadUtil.saveFiles(fileUploads, Post.acceptedFileFields, configManager)

            if (savedFiles.isNotEmpty()) {
                thumbnailUrl = AppConstants.POST_THUMBNAIL_URL_PREFIX + savedFiles[0].path.split("/").last()
            }
        }

        val post = Post(
            title = title,
            categoryId = categoryId,
            writerUserId = userId,
            text = text,
            thumbnailUrl = thumbnailUrl,
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