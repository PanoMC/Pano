package com.panomc.platform.route.api.setup

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.backup.PanoBackupManager
import com.panomc.platform.model.*
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository

/**
 * The setup-mode restore job (`GET /api/setup/restore`). Not a [SetupApi]: a finished restore
 * installs a config that says setup is done, and the wizard still has to read the result before
 * Pano restarts. It exposes nothing but the job (no data from the archive).
 */
@Endpoint
class SetupGetRestoreAPI(private val panoBackupManager: PanoBackupManager) : Api() {
    override val paths = listOf(Path("/api/setup/restore", RouteType.GET))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository).build()

    override suspend fun onBeforeHandle(context: RoutingContext) {
        checkDemoMode(context)
    }

    override suspend fun handle(context: RoutingContext): Result =
        Successful(mapOf("job" to panoBackupManager.setupService?.job?.toJson()))
}
