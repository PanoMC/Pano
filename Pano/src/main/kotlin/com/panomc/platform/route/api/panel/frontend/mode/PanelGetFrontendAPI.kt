package com.panomc.platform.route.api.panel.frontend.mode

import com.panomc.platform.UIManager
import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.panel.permission.ManageViewPermission
import com.panomc.platform.config.ConfigManager
import com.panomc.platform.model.*
import com.panomc.platform.ui.CustomAppInstaller
import com.panomc.platform.ui.ThemeUiController
import com.panomc.platform.util.UsageMode
import io.vertx.ext.web.RoutingContext

/** The front-end mode and everything that goes with it (doc 05 §8). */
@Endpoint
class PanelGetFrontendAPI(
    private val configManager: ConfigManager,
    private val uiManager: UIManager,
    private val themeUiController: ThemeUiController,
    private val customAppInstaller: CustomAppInstaller,
    private val authProvider: AuthProvider
) : PanelApi() {
    override val usageModes = UsageMode.WITH_WEBSITE

    override val paths = listOf(Path("/frontend", RouteType.GET))

    override suspend fun handle(context: RoutingContext): Result {
        authProvider.requirePermission(ManageViewPermission(), context)

        return Successful(frontendState(configManager, uiManager, themeUiController, customAppInstaller))
    }
}
