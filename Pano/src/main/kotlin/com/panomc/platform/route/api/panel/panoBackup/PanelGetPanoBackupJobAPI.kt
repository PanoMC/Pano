package com.panomc.platform.route.api.panel.panoBackup

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.panel.permission.ManagePanoBackupsPermission
import com.panomc.platform.backup.PanoBackupManager
import com.panomc.platform.model.*
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository

/** The running or last backup/restore job, for polling (`GET /api/panel/pano-backups/job`). */
@Endpoint
class PanelGetPanoBackupJobAPI(
    private val authProvider: AuthProvider,
    private val panoBackupManager: PanoBackupManager
) : PanelApi() {
    override val paths = listOf(Path("/api/panel/pano-backups/job", RouteType.GET))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository).build()

    override suspend fun handle(context: RoutingContext): Result {
        authProvider.requirePermission(ManagePanoBackupsPermission(), context)

        return Successful(mapOf("job" to panoBackupManager.service.job?.toJson()))
    }
}
