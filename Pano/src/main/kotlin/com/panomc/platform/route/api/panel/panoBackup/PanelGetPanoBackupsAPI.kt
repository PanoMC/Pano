package com.panomc.platform.route.api.panel.panoBackup

import com.panomc.platform.PlatformStateManager
import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.panel.permission.ManagePanoBackupsPermission
import com.panomc.platform.backup.PanoBackupManager
import com.panomc.platform.model.*
import io.vertx.core.json.JsonArray
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.kotlin.coroutines.coAwait

/** Local Pano backups, the running/last job and the settings (`GET /api/panel/pano-backups`). */
@Endpoint
class PanelGetPanoBackupsAPI(
    private val authProvider: AuthProvider,
    private val panoBackupManager: PanoBackupManager,
    private val platformStateManager: PlatformStateManager
) : PanelApi() {
    override val paths = listOf(Path("/api/panel/pano-backups", RouteType.GET))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository).build()

    override suspend fun handle(context: RoutingContext): Result {
        authProvider.requirePermission(ManagePanoBackupsPermission(), context)

        val backups = context.vertx().executeBlocking { panoBackupManager.store.list() }.coAwait()
        val service = panoBackupManager.service

        return Successful(
            mapOf(
                "backups" to JsonArray(backups.map { it.toJson() }),
                "usedBytes" to backups.sumOf { it.sizeBytes },
                "job" to service.job?.toJson(),
                "busy" to service.isBusy(),
                "settings" to panoBackupManager.getSettings().toJson(),
                "restartRequired" to platformStateManager.restartRequired
            )
        )
    }
}
