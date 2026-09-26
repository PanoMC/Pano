package com.panomc.platform.route.api.panel.panoBackup

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.panel.permission.ManagePanoBackupsPermission
import com.panomc.platform.backup.PanoBackupManager
import com.panomc.platform.backup.PanoBackupSettings
import com.panomc.platform.error.BadRequest
import com.panomc.platform.model.*
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.RequestPredicate
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Bodies.json
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.enumSchema
import io.vertx.json.schema.common.dsl.Schemas.intSchema
import io.vertx.json.schema.common.dsl.Schemas.objectSchema

/** Saves the local backup schedule (`PUT /api/panel/pano-backups/settings`). */
@Endpoint
class PanelUpdatePanoBackupSettingsAPI(
    private val authProvider: AuthProvider,
    private val panoBackupManager: PanoBackupManager
) : PanelApi() {
    override val paths = listOf(Path("/api/panel/pano-backups/settings", RouteType.PUT))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .body(
                json(
                    objectSchema()
                        .requiredProperty("schedule", enumSchema(*PanoBackupSettings.Schedule.values().map { it.name }.toTypedArray()))
                        .requiredProperty("hour", intSchema())
                        .requiredProperty("keep", intSchema())
                )
            )
            .predicate(RequestPredicate.BODY_REQUIRED)
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        authProvider.requirePermission(ManagePanoBackupsPermission(), context)

        val settings = PanoBackupSettings.fromJson(getParameters(context).body().jsonObject) ?: throw BadRequest()

        panoBackupManager.saveSettings(settings)

        return Successful(mapOf("settings" to settings.toJson()))
    }
}
