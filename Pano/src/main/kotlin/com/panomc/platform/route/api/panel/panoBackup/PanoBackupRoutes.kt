package com.panomc.platform.route.api.panel.panoBackup

import com.panomc.platform.backup.PanoBackupException
import com.panomc.platform.backup.PanoBackupJob
import com.panomc.platform.backup.PanoBackupService
import com.panomc.platform.error.BadRequest
import com.panomc.platform.error.PanoBackupBusy
import io.vertx.ext.web.FileUpload
import io.vertx.ext.web.RoutingContext
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/** Shared bits of the Pano Backup routes (panel + setup). */
object PanoBackupRoutes {
    /** The multipart field an archive is uploaded under. */
    const val FILE_PART = "file"

    /** Upper bound for one uploaded archive. */
    const val MAX_UPLOAD_BYTES = 64L * 1024 * 1024 * 1024

    const val MAX_PASSPHRASE_LENGTH = 1024

    /** Runs [start], turning "another operation is running" into a 409. */
    fun startJob(start: () -> PanoBackupJob): PanoBackupJob = try {
        start()
    } catch (e: PanoBackupException) {
        if (e.code == PanoBackupService.BUSY) throw PanoBackupBusy() else throw e
    }

    fun passphrase(value: String?): CharArray? {
        if (value != null && value.length > MAX_PASSPHRASE_LENGTH) {
            throw BadRequest()
        }

        return value?.takeIf { it.isNotEmpty() }?.toCharArray()
    }

    fun upload(context: RoutingContext): FileUpload {
        val uploads = context.fileUploads()

        return uploads.firstOrNull { it.name() == FILE_PART } ?: uploads.singleOrNull() ?: throw BadRequest()
    }

    /**
     * Moves an uploaded archive out of the body handler's spool (deleted when the request ends)
     * into [tempDir], where the restore job owns it.
     */
    fun claimUpload(upload: FileUpload, tempDir: File): File {
        tempDir.mkdirs()

        val target = File(tempDir, "restore-upload-${UUID.randomUUID()}.panoarc")
        val source = File(upload.uploadedFileName())

        try {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } catch (_: Exception) {
            source.copyTo(target, overwrite = true)
        }

        return target
    }
}
