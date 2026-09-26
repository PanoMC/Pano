package com.panomc.platform.route.api.setup

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.archive.instance.InstanceLayout
import com.panomc.platform.backup.PanoBackupManager
import com.panomc.platform.config.ConfigManager
import com.panomc.platform.error.FileTooLarge
import com.panomc.platform.model.*
import com.panomc.platform.route.api.panel.panoBackup.PanoBackupRoutes
import io.vertx.core.Handler
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.handler.BodyHandler
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.json.schema.SchemaRepository
import io.vertx.kotlin.coroutines.coAwait
import java.io.File

/**
 * setup-ui "restore from file" (`POST /api/setup/restore`, multipart: `file`, `passphrase`, and the
 * target database `host`, `dbName`, `username`, `password` — omitted = the database from setup
 * step 2). The archive's site replaces the setup: its config (with this machine's database, server
 * and uploads keys) is installed and Pano restarts. Poll `GET /api/setup/restore`.
 */
@Endpoint
class SetupRestoreAPI(
    private val configManager: ConfigManager,
    private val panoBackupManager: PanoBackupManager
) : SetupApi() {
    override val paths = listOf(Path("/api/setup/restore", RouteType.POST))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler? = null

    override fun bodyHandler(): Handler<RoutingContext> = BodyHandler.create()
        .setDeleteUploadedFilesOnEnd(true)
        .setUploadsDirectory(configManager.config.fileUploadsFolder + File.separator + "temp")
        .setBodyLimit(PanoBackupRoutes.MAX_UPLOAD_BYTES)

    override suspend fun handle(context: RoutingContext): Result {
        val request = context.request()
        val upload = PanoBackupRoutes.upload(context)

        if (upload.size() > PanoBackupRoutes.MAX_UPLOAD_BYTES) {
            throw FileTooLarge()
        }

        val host = request.getFormAttribute("host")?.trim()

        val database = if (host.isNullOrEmpty()) null else JsonObject()
            .put("host", host)
            .put("name", request.getFormAttribute("dbName") ?: "")
            .put("username", request.getFormAttribute("username") ?: "")
            .put("password", request.getFormAttribute("password") ?: "")

        val passphrase = PanoBackupRoutes.passphrase(request.getFormAttribute("passphrase"))
        val tempDir = InstanceLayout.current(configManager.config).tempDir
        val source = context.vertx().executeBlocking { PanoBackupRoutes.claimUpload(upload, tempDir) }.coAwait()

        val job = try {
            val service = panoBackupManager.prepareSetupService(database)

            PanoBackupRoutes.startJob {
                service.startRestore(source, passphrase, deleteSource = true, safetyArchive = false, maintenance = false)
            }
        } catch (e: Throwable) {
            source.delete()

            throw e
        }

        return Successful(mapOf("job" to job.toJson()))
    }
}
