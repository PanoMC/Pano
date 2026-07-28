package com.panomc.platform.route.api.panel.maintenance

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.panel.permission.ManagePlatformSettingsPermission
import com.panomc.platform.maintenance.MaintenanceModeManager
import com.panomc.platform.maintenance.MaintenanceModeManager.PageTemplate
import com.panomc.platform.model.*
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.RequestPredicate
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Bodies.json
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.booleanSchema
import io.vertx.json.schema.common.dsl.Schemas.objectSchema

/**
 * Renders the page the given drafts would produce, without saving anything. The panel editor shows
 * the result under the markup it is editing: the composed page is the only faithful preview, and a
 * hand-written copy of the template in the panel would drift from it on the first change.
 *
 * Every block is populated at once so the editor shows the whole interface, not the subset a
 * particular visitor would get.
 */
@Endpoint
class PanelPreviewMaintenancePageAPI(
    private val authProvider: AuthProvider,
    private val maintenanceModeManager: MaintenanceModeManager
) : PanelApi() {
    override val paths = listOf(Path("/api/panel/maintenance/preview", RouteType.POST))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .body(
                json(
                    objectSchema()
                        .requiredProperty("templates", objectSchema())
                        .optionalProperty("showSiteLogo", booleanSchema())
                )
            )
            .predicate(RequestPredicate.BODY_REQUIRED)
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        authProvider.requirePermission(ManagePlatformSettingsPermission(), context)

        val data = getParameters(context).body().jsonObject
        val templates = data.getJsonObject("templates")

        val drafts = PageTemplate.entries.mapNotNull { template ->
            templates.getString(template.name)?.takeIf { it.isNotBlank() }?.let { template to it }
        }.toMap()

        return Successful(
            mapOf(
                "html" to maintenanceModeManager.renderPreviewOf(
                    drafts,
                    data.getBoolean("showSiteLogo", true)
                )
            )
        )
    }
}
