package com.panomc.platform.route.api.panel.panoBackup

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.panel.permission.ManagePanoBackupsPermission
import com.panomc.platform.backup.PanoBackupAudit
import com.panomc.platform.backup.PanoBackupJob
import com.panomc.platform.backup.PanoBackupManager
import com.panomc.platform.error.PanoBackupRestoring
import com.panomc.platform.model.*
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository

/** The running or last backup/restore job, for polling (`GET /api/panel/pano-backups/job`). */
@Endpoint
class PanelGetPanoBackupJobAPI(
    private val authProvider: AuthProvider,
    private val panoBackupAudit: PanoBackupAudit,
    private val panoBackupManager: PanoBackupManager
) : PanelApi() {
    override val paths = listOf(Path("/api/panel/pano-backups/job", RouteType.GET))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository).build()

    /**
     * Two things a restore does to the session of the page polling this: while it replaces the
     * database the session tables are gone for a moment ("still restoring", not a 500), and once
     * it is applied they are the backup's, which may not know this session any more. The admin who
     * started the restore keeps reading how it went either way.
     */
    override suspend fun onBeforeHandle(context: RoutingContext) {
        try {
            super.onBeforeHandle(context)
        } catch (e: Throwable) {
            if (!isOwnRestore(context)) {
                throw restoring(e)
            }

            context.put(OWN_RESTORE, true)
        }
    }

    override suspend fun handle(context: RoutingContext): Result {
        if (context.get<Boolean>(OWN_RESTORE) != true) {
            try {
                authProvider.requirePermission(ManagePanoBackupsPermission(), context)
            } catch (e: Throwable) {
                if (!isOwnRestore(context)) {
                    throw restoring(e)
                }
            }
        }

        return Successful(mapOf("job" to panoBackupManager.service.job?.toJson()))
    }

    private fun isOwnRestore(context: RoutingContext): Boolean =
        panoBackupManager.service.job?.type == PanoBackupJob.Type.RESTORE && panoBackupAudit.isRestoreStarter(context)

    private fun restoring(e: Throwable): Throwable {
        val job = panoBackupManager.service.job

        return if (e !is Error && e is Exception && job?.type == PanoBackupJob.Type.RESTORE && job.status == PanoBackupJob.Status.RUNNING) {
            PanoBackupRestoring()
        } else {
            e
        }
    }

    private companion object {
        const val OWN_RESTORE = "panoBackupOwnRestore"
    }
}
