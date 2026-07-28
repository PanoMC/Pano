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
import io.vertx.json.schema.common.dsl.Schemas.stringSchema

/**
 * Renders the page the given drafts would produce, without saving anything. The panel editor shows
 * the result under the markup it is editing: the composed page is the only faithful preview, and a
 * hand-written copy of the template in the panel would drift from it on the first change.
 *
 * `focus` names the block being edited, and the preview simulates the page state that block
 * actually appears in — the login form tab renders the login page, the skip tab renders what a
 * bypasser sees. Editing a block you cannot see is the thing this avoids.
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
                        // The block being edited; decides which page state the preview simulates.
                        .optionalProperty("focus", stringSchema())
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

        val focus = data.getString("focus")
            ?.let { name -> PageTemplate.entries.firstOrNull { it.name == name } }
            ?: PageTemplate.PAGE

        return Successful(
            mapOf(
                "html" to maintenanceModeManager.renderPreviewOf(
                    drafts,
                    data.getBoolean("showSiteLogo", true),
                    focus
                )
            )
        )
    }
}
