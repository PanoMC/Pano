package com.panomc.platform.route.api.panel.frontend.mode

import com.panomc.platform.UIManager
import com.panomc.platform.config.ConfigManager
import com.panomc.platform.ui.CustomAppInstaller
import com.panomc.platform.ui.ThemeUiController

/**
 * The body of `GET /panel/frontend` and of a successful `PUT` (doc 05 §8): the stored `frontend {}`
 * block in camel case, the uploaded apps, and whether the front-end is bound right now.
 */
internal fun frontendState(
    configManager: ConfigManager,
    uiManager: UIManager,
    themeUiController: ThemeUiController,
    customAppInstaller: CustomAppInstaller
): Map<String, Any?> {
    val frontend = configManager.config.effectiveFrontend

    return mapOf(
        "mode" to frontend.parsedMode.name,
        "customAppId" to frontend.customApp,
        "upstreamUrl" to frontend.upstreamUrl,
        "siteUrl" to frontend.siteUrl,
        "descriptorUrl" to frontend.descriptorUrl,
        "devUrl" to frontend.devUrl,
        "customApps" to customAppInstaller.list().map { app ->
            mapOf(
                "id" to app.id,
                "title" to app.title,
                "version" to app.version,
                "author" to app.author,
                "description" to app.description,
                "apiLevel" to app.apiLevel,
                "installedAt" to app.installedAt,
                "active" to uiManager.isCustomAppActive(app.id)
            )
        },
        "running" to themeUiController.isRunning,
        "activeId" to uiManager.activeFrontendId(),
        // dev-url only applies in THEME mode with Development Mode on; the panel shows why it does not.
        "devUrlActive" to (uiManager.devServerTarget() != null)
    )
}
