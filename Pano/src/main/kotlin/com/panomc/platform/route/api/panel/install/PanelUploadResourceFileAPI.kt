package com.panomc.platform.route.api.panel.install

import com.panomc.platform.AppConstants
import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.panel.permission.ManageAddonsPermission
import com.panomc.platform.auth.panel.permission.ManageViewPermission
import com.panomc.platform.error.InvalidResourceFile
import com.panomc.platform.error.NoPermission
import com.panomc.platform.model.*
import io.vertx.core.Handler
import io.vertx.core.Vertx
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.handler.BodyHandler
import io.vertx.json.schema.SchemaRepository
import io.vertx.kotlin.coroutines.coAwait
import java.io.File

@Endpoint
class PanelUploadResourceFileAPI(
    private val vertx: Vertx,
    private val authProvider: AuthProvider
) : PanelApi() {
    override val paths = listOf(Path("/install/upload", RouteType.PUT))

    override fun bodyHandler(): Handler<RoutingContext> =
        BodyHandler.create()
            .setDeleteUploadedFilesOnEnd(true)
            .setBodyLimit(100 * 1024 * 1024) // 100MB

    override fun getValidationHandler(schemaRepository: SchemaRepository) = null

    override suspend fun handle(context: RoutingContext): Result {
        val canManageAddons = authProvider.hasPermission(ManageAddonsPermission(), context)

        if (!(canManageAddons || authProvider.hasPermission(ManageViewPermission(), context))) {
            throw NoPermission()
        }

        val fileUploads = context.fileUploads()

        if (fileUploads.size > 1 || fileUploads.isEmpty()) {
            throw InvalidResourceFile()
        }

        val file = fileUploads[0]

        // The name the browser sent is only a hint: keep its last segment, and only a theme zip or a plugin jar.
        val fileName = safeFileName(file.fileName()) ?: throw InvalidResourceFile()

        if (fileName.lowercase().endsWith(".jar") && !canManageAddons) {
            throw NoPermission()
        }

        val uploadedFile = File(file.uploadedFileName())
        val tempFolder = File(AppConstants.TEMP_FOLDER)

        if (!tempFolder.exists() || !tempFolder.isDirectory) {
            tempFolder.deleteRecursively()
            tempFolder.mkdirs()
        }

        val temporaryFilePath = AppConstants.TEMP_FOLDER + File.separator + fileName

        vertx.executeBlocking<Unit> {
            uploadedFile.copyTo(File(temporaryFilePath), true)
        }.coAwait()

        return Successful(
            mapOf(
                "data" to mapOf(
                    "fileName" to fileName
                )
            )
        )
    }

    override suspend fun getFailureHandler(context: RoutingContext) {
        if (context.failure() == null) {
            throw InvalidResourceFile()
        }
    }

    companion object {
        private val ALLOWED_EXTENSIONS = listOf(".zip", ".jar")

        /**
         * [raw] reduced to a plain file name that stays inside the folder it is joined to: the last path segment
         * (either separator), no control characters, not `.` / `..`, and a `.zip` or `.jar` name. Null otherwise.
         */
        fun safeFileName(raw: String?): String? {
            val name = raw?.substringAfterLast('/')?.substringAfterLast('\\')?.trim() ?: return null

            if (name.isEmpty() || name.length > 255 || name == "." || name == ".." || name.any { it.isISOControl() }) {
                return null
            }

            if (name.contains(':') || ALLOWED_EXTENSIONS.none { name.lowercase().endsWith(it) }) {
                return null
            }

            return name
        }
    }
}