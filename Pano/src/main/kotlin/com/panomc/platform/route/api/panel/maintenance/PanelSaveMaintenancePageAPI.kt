package com.panomc.platform.route.api.panel.maintenance

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.panel.permission.ManagePlatformSettingsPermission
import com.panomc.platform.config.ConfigManager
import com.panomc.platform.config.PanoConfig
import com.panomc.platform.error.BadRequest
import com.panomc.platform.maintenance.MaintenanceModeManager
import com.panomc.platform.maintenance.MaintenanceModeManager.PageTemplate
import com.panomc.platform.model.*
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.RequestPredicate
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Bodies.json
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.objectSchema

/**
 * Writes the maintenance page's blocks verbatim from the panel editor and flips
 * `maintenance.custom-page`, after which Pano neither composes the page from the title/message/CSS
 * fields nor replaces any of these files when an update ships a new default design.
 *
 * The markup becomes a live Handlebars template, so an admin can write directives into it. That is
 * the point of the mode and the reason it is gated behind Manage Platform Settings: the only values
 * a template can reach are the slots Pano passes in.
 */
@Endpoint
class PanelSaveMaintenancePageAPI(
    private val authProvider: AuthProvider,
    private val configManager: ConfigManager,
    private val maintenanceModeManager: MaintenanceModeManager
) : PanelApi() {
    override val paths = listOf(Path("/api/panel/maintenance/page", RouteType.POST))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .body(json(objectSchema().requiredProperty("templates", objectSchema())))
            .predicate(RequestPredicate.BODY_REQUIRED)
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        authProvider.requirePermission(ManagePlatformSettingsPermission(), context)

        val templates = getParameters(context).body().jsonObject.getJsonObject("templates")

        val updates = PageTemplate.entries.mapNotNull { template ->
            val html = templates.getString(template.name) ?: return@mapNotNull null

            if (html.isBlank() || html.length > MAX_TEMPLATE_LENGTH) {
                throw BadRequest()
            }

            template to html
        }

        if (updates.isEmpty()) {
            throw BadRequest()
        }

        val currentMaintenance: PanoConfig.Companion.MaintenanceConfig? = configManager.config.maintenance
        val maintenanceConfig = currentMaintenance ?: PanoConfig.Companion.MaintenanceConfig().also {
            configManager.config.maintenance = it
        }

        maintenanceConfig.customPage = true

        configManager.saveConfig()

        updates.forEach { (template, html) -> maintenanceModeManager.saveTemplateSource(template, html) }

        return Successful()
    }

    companion object {
        private const val MAX_TEMPLATE_LENGTH = 512 * 1024
    }
}
