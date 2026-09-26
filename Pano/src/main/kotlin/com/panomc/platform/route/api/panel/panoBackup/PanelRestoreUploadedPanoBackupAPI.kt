package com.panomc.platform.route.api.panel.panoBackup

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.archive.instance.InstanceLayout
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.panel.permission.ManagePanoBackupsPermission
import com.panomc.platform.backup.PanoBackupManager
import com.panomc.platform.config.ConfigManager
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.error.CurrentPasswordNotCorrect
import com.panomc.platform.error.FileTooLarge
import com.panomc.platform.model.*
import io.vertx.core.Handler
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.handler.BodyHandler
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.json.schema.SchemaRepository
import io.vertx.kotlin.coroutines.coAwait
import java.io.File

/**
 * Restores an uploaded `.panoarc` (or plain export zip) over this Pano
 * (`POST /api/panel/pano-backups/restore`, multipart: `file`, `currentPassword`, `passphrase`).
 * Same flow as restoring a local backup; the upload is deleted once the job ends.
 */
@Endpoint
class PanelRestoreUploadedPanoBackupAPI(
    private val authProvider: AuthProvider,
    private val databaseManager: DatabaseManager,
    private val configManager: ConfigManager,
    private val panoBackupManager: PanoBackupManager
) : PanelApi() {
    override val paths = listOf(Path("/api/panel/pano-backups/restore", RouteType.POST))

    // Multipart: nothing a JSON schema could check.
    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler? = null

    override fun bodyHandler(): Handler<RoutingContext> = BodyHandler.create()
        .setDeleteUploadedFilesOnEnd(true)
        .setUploadsDirectory(configManager.config.fileUploadsFolder + File.separator + "temp")
        .setBodyLimit(PanoBackupRoutes.MAX_UPLOAD_BYTES)

    override suspend fun handle(context: RoutingContext): Result {
        authProvider.requirePermission(ManagePanoBackupsPermission(), context)

        val request = context.request()
        val userId = authProvider.getUserIdFromRoutingContext(context)

        if (!databaseManager.userDao.isPasswordCorrectWithId(userId, request.getFormAttribute("currentPassword") ?: "", getSqlClient())) {
            throw CurrentPasswordNotCorrect()
        }

        val upload = PanoBackupRoutes.upload(context)

        if (upload.size() > PanoBackupRoutes.MAX_UPLOAD_BYTES) {
            throw FileTooLarge()
        }

        val passphrase = PanoBackupRoutes.passphrase(request.getFormAttribute("passphrase"))
        val tempDir = InstanceLayout.current(configManager.config).tempDir
        val source = context.vertx().executeBlocking { PanoBackupRoutes.claimUpload(upload, tempDir) }.coAwait()

        val job = try {
            PanoBackupRoutes.startJob { panoBackupManager.service.startRestore(source, passphrase, deleteSource = true) }
        } catch (e: Throwable) {
            source.delete()

            throw e
        }

        return Successful(mapOf("job" to job.toJson()))
    }
}
