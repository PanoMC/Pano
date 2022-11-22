package com.panomc.platform.route.api.panel.post

import com.panomc.platform.ErrorCode
import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.config.ConfigManager
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.Post
import com.panomc.platform.db.model.Post.Companion.deleteThumbnailFile
import com.panomc.platform.model.*
import com.panomc.platform.util.*
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Bodies.multipartFormData
import io.vertx.ext.web.validation.builder.Parameters
import io.vertx.json.schema.SchemaParser
import io.vertx.json.schema.common.dsl.Schemas.*

@Endpoint
class PanelUpdatePostAPI(
    private val databaseManager: DatabaseManager,
    setupManager: SetupManager,
    private val authProvider: AuthProvider,
    private val configManager: ConfigManager
) : PanelApi(setupManager, authProvider) {
    override val routeType = RouteType.PUT

    override val routes = arrayListOf("/api/panel/posts/:id")

    override fun getValidationHandler(schemaParser: SchemaParser): ValidationHandler =
        ValidationHandler.builder(schemaParser)
            .pathParameter(Parameters.param("id", numberSchema()))
            .body(
                multipartFormData(
                    objectSchema()
                        .property("title", stringSchema())
                        .property("category", numberSchema())
                        .property("text", stringSchema())
                        .optionalProperty("removeThumbnail", booleanSchema())
                )
            )
            .build()

    override suspend fun handler(context: RoutingContext): Result {
        val parameters = getParameters(context)
        val data = parameters.body().jsonObject

        val fileUploads = context.fileUploads()

        val id = parameters.pathParameter("id").long
        val title = data.getString("title")
        val categoryId = data.getLong("category")
        val text = data.getString("text")
        val removeThumbnail = data.getBoolean("removeThumbnail") ?: false
        val url = TextUtil.convertStringToUrl(title, 32)

        var thumbnailUrl = ""

        val userId = authProvider.getUserIdFromRoutingContext(context)

        val sqlConnection = createConnection(databaseManager, context)

        val postInDb = databaseManager.postDao.getById(id, sqlConnection) ?: throw Error(ErrorCode.NOT_EXISTS)

        if (!removeThumbnail) {
            thumbnailUrl = postInDb.thumbnailUrl

            if (fileUploads.size > 0) {
                val savedFiles = FileUploadUtil.saveFiles(fileUploads, Post.acceptedFileFields, configManager)

                if (savedFiles.isNotEmpty()) {
                    postInDb.deleteThumbnailFile(configManager)

                    thumbnailUrl = AppConstants.POST_THUMBNAIL_URL_PREFIX + savedFiles[0].path.split("/").last()
                }
            }
        } else {
            postInDb.deleteThumbnailFile(configManager)
        }

        val post = Post(
            id = id,
            title = title,
            categoryId = categoryId,
            writerUserId = userId,
            text = text,
            thumbnailUrl = thumbnailUrl,
            url = "$url-$id"
        )

        databaseManager.postDao.updateAndPublish(userId, post, sqlConnection)

        return Successful()
    }
}