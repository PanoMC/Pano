package com.panomc.platform.route.api.panel.install

import com.panomc.platform.AppConstants
import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.error.InvalidResourceFile
import com.panomc.platform.model.*
import com.panomc.platform.util.TimeUtil.getCurrentTimeStamp
import io.vertx.core.Handler
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.handler.BodyHandler
import io.vertx.json.schema.SchemaRepository
import java.io.File

@Endpoint
class PanelUploadResourceFileAPI : PanelApi() {
    override val paths = listOf(Path("/api/panel/install/upload", RouteType.PUT))

    override fun bodyHandler(): Handler<RoutingContext> =
        BodyHandler.create()
            .setDeleteUploadedFilesOnEnd(true)
            .setBodyLimit(100 * 1024 * 1024) // 100MB

    override fun getValidationHandler(schemaRepository: SchemaRepository) = null

    override suspend fun handle(context: RoutingContext): Result? {
        val fileUploads = context.fileUploads()

        if (fileUploads.size > 1 || fileUploads.isEmpty()) {
            throw InvalidResourceFile()
        }

        val file = fileUploads[0]

        file.fileName()

        val uploadedFile = File(file.uploadedFileName())
        val tempFolder = File(AppConstants.TEMP_FOLDER)

        if (!tempFolder.exists() || !tempFolder.isDirectory) {
            tempFolder.deleteRecursively()
            tempFolder.mkdirs()
        }

        val split = file.fileName().split(".")
        val tempFileName = "pano-upload_" + getCurrentTimeStamp() + (if (split.size > 1) "." + split[1] else "")
        val temporaryFilePath = AppConstants.TEMP_FOLDER + File.separator + tempFileName

        uploadedFile.copyTo(File(temporaryFilePath))

        return Successful(
            mapOf(
                "data" to mapOf(
                    "fileName" to tempFileName
                )
            )
        )
    }

    override suspend fun getFailureHandler(context: RoutingContext) {
        if (context.failure() == null) {
            throw InvalidResourceFile()
        }
    }
}