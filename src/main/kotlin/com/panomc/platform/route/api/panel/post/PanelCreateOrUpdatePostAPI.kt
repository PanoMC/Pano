package com.panomc.platform.route.api.panel.post

import com.panomc.platform.ErrorCode
import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.config.ConfigManager
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.Post
import com.panomc.platform.db.model.Post.Companion.deleteThumbnailFile
import com.panomc.platform.model.*
import com.panomc.platform.util.*
import io.vertx.ext.web.FileUpload
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Bodies.multipartFormData
import io.vertx.ext.web.validation.builder.Parameters.optionalParam
import io.vertx.json.schema.SchemaParser
import io.vertx.json.schema.common.dsl.Schemas.*

@Endpoint
class PanelCreateOrUpdatePostAPI(
    private val databaseManager: DatabaseManager,
    setupManager: SetupManager,
    private val authProvider: AuthProvider,
    private val configManager: ConfigManager
) : PanelApi(setupManager, authProvider) {
    override val paths = listOf(
        Path("/api/panel/posts/:id", RouteType.PUT),
        Path("/api/panel/post", RouteType.POST)
    )

    override fun getValidationHandler(schemaParser: SchemaParser): ValidationHandler =
        ValidationHandler.builder(schemaParser)
            .pathParameter(optionalParam("id", numberSchema()))
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

        val id = parameters.pathParameter("id")?.long
        val title = data.getString("title")
        val categoryId = data.getLong("category")
        val text = data.getString("text")
        val removeThumbnail = data.getBoolean("removeThumbnail") ?: false
        val url = TextUtil.convertStringToUrl(title, 32)

        var thumbnailUrl = ""
        val body = mutableMapOf<String, Any?>()

        val userId = authProvider.getUserIdFromRoutingContext(context)

        val sqlConnection = createConnection(databaseManager, context)

        if (id == null) {
            thumbnailUrl = saveUploadedFileAndGetThumbnailUrl(fileUploads, null)
        } else {
            val postInDb = databaseManager.postDao.getById(id, sqlConnection) ?: throw Error(ErrorCode.NOT_EXISTS)

            if (removeThumbnail) {
                postInDb.deleteThumbnailFile(configManager)
            } else {
                thumbnailUrl = saveUploadedFileAndGetThumbnailUrl(fileUploads, postInDb)
            }
        }

        val post = Post(
            id = id ?: -1,
            title = title,
            categoryId = categoryId,
            writerUserId = userId,
            text = text,
            thumbnailUrl = thumbnailUrl,
            url = if (id == null) url else "$url-$id"
        )

        if (id == null) {
            val postId = databaseManager.postDao.insertAndPublish(post, sqlConnection)

            databaseManager.postDao.updatePostUrlByUrl(url, "$url-$postId", sqlConnection)

            body["id"] = postId
        } else {
            databaseManager.postDao.updateAndPublish(userId, post, sqlConnection)
        }

        return Successful(body)
    }

    private fun saveUploadedFileAndGetThumbnailUrl(fileUploads: List<FileUpload>, postInDb: Post?): String {
        var thumbnailUrl = postInDb?.thumbnailUrl ?: ""
        val savedFiles = FileUploadUtil.saveFiles(fileUploads, Post.acceptedFileFields, configManager)

        if (savedFiles.isNotEmpty()) {
            postInDb?.deleteThumbnailFile(configManager)

            thumbnailUrl = AppConstants.POST_THUMBNAIL_URL_PREFIX + savedFiles[0].path.split("/").last()
        }

        return thumbnailUrl
    }
}