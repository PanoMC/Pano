package com.panomc.platform.route.api.panel.theme

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.panel.permission.ManageViewPermission
import com.panomc.platform.model.*
import com.panomc.platform.ui.ThemeCompatibility
import com.panomc.platform.util.UsageMode
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.json.schema.SchemaRepository
import io.vertx.kotlin.coroutines.coAwait

/**
 * `GET /panel/theme/compatibility` -- which overrides of the active theme the plugins no longer match
 * (doc 01 section 7). Computed from the theme's `core-meta.json` and each active plugin's contract
 * files; `UNKNOWN` when the theme has no `core-meta.json`. Never blocks anything.
 */
@Endpoint
class PanelGetThemeCompatibilityAPI(
    private val authProvider: AuthProvider,
    private val themeCompatibility: ThemeCompatibility
) : PanelApi() {
    override val usageModes = UsageMode.WITH_WEBSITE

    override val paths = listOf(Path("/theme/compatibility", RouteType.GET))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler? = null

    override suspend fun handle(context: RoutingContext): Result {
        authProvider.requirePermission(ManageViewPermission(), context)

        val report = context.vertx().executeBlocking<Map<String, Any?>> { themeCompatibility.report() }.coAwait()

        return Successful(report)
    }
}
