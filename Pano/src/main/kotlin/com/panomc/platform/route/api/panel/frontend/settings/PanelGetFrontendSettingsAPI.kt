package com.panomc.platform.route.api.panel.frontend.settings

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.panel.permission.ManageViewPermission
import com.panomc.platform.frontend.FrontendSettings
import com.panomc.platform.model.*
import com.panomc.platform.util.UsageMode
import io.vertx.ext.web.RoutingContext

/**
 * The settings form of the active front-end (doc 05 section 9): its `settingsSchema` and the stored values with the
 * defaults filled in. `hasSchema: false` means the front-end declares no `fields` and keeps its own settings page.
 */
@Endpoint
class PanelGetFrontendSettingsAPI(
    private val frontendSettings: FrontendSettings,
    private val authProvider: AuthProvider
) : PanelApi() {
    override val usageModes = UsageMode.WITH_WEBSITE

    override val paths = listOf(Path("/frontend/settings", RouteType.GET))

    override suspend fun handle(context: RoutingContext): Result {
        authProvider.requirePermission(ManageViewPermission(), context)

        return Successful(frontendSettingsState(frontendSettings.read(getSqlClient())))
    }
}
