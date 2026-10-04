package com.panomc.platform.ui

import com.panomc.platform.AppConstants.DEFAULT_THEME_ID
import com.panomc.platform.UIManager
import com.panomc.platform.config.ConfigManager
import com.panomc.platform.license.LicenseRequiredException
import com.panomc.platform.model.Route
import io.vertx.ext.web.Router
import org.slf4j.Logger
import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.context.annotation.Lazy
import org.springframework.context.annotation.Scope
import org.springframework.stereotype.Component

/**
 * Starting and stopping the theme, in one place, because three callers now need it.
 *
 * It used to live inside the two panel endpoints that did it on purpose — `POST /api/panel/themes`
 * and `DELETE /api/panel/themes`. Switching the usage mode has to do the same thing (SERVERS mode
 * has no website and should not be paying for a second Bun process), and a third copy of "start
 * the theme, and if its licence is refused fall back to vanilla and persist that" is a third place
 * for the fallback to be subtly wrong.
 *
 * The licence fallback is the reason this is not two one-line calls into [UIManager]. A premium
 * theme whose licence cannot be verified must not stop the site from serving: it falls back to the
 * bundled vanilla theme and writes that choice to the config, so the next boot is clean too. Every
 * caller needs that, and every caller needs to be able to tell the operator it happened.
 */
@Lazy
@Component
@Scope(value = ConfigurableBeanFactory.SCOPE_SINGLETON)
class ThemeUiController(
    private val uiManager: UIManager,
    private val configManager: ConfigManager,
    @param:Lazy private val router: Router,
    private val logger: Logger
) {
    /**
     * What a start did.
     *
     * [fellBackFrom] is non-null when the theme that was asked for could not be licensed, and is
     * what lets a caller say "running vanilla, your premium theme's licence was refused" instead
     * of reporting a plain success.
     */
    data class StartResult(
        val themeId: String,
        val alreadyRunning: Boolean,
        val fellBackFrom: String? = null,
        val licenseDeniedReason: String? = null,
        val message: String? = null
    )

    /** Whether a theme is currently bound to the wildcard route. */
    val isRunning: Boolean get() = uiManager.activatedUIList.containsKey(Route.Type.THEME_UI)

    /** The theme that would be started, which is also the one that is running when one is. */
    val activeTheme: String get() = uiManager.activeTheme

    /**
     * Starts the configured theme and binds its proxy route.
     *
     * Idempotent: a theme that is already bound is reported as such rather than restarted, which
     * is what makes this safe to call from a settings save that may not have changed anything.
     */
    suspend fun start(): StartResult {
        if (isRunning) {
            return StartResult(themeId = uiManager.activeTheme, alreadyRunning = true)
        }

        val requested = uiManager.activeTheme

        try {
            uiManager.startUI(requested)
        } catch (e: LicenseRequiredException) {
            // The configured premium theme cannot be started because its licence is missing,
            // expired or unverifiable. Falling back keeps the site serving; persisting the
            // fallback keeps the next boot from repeating the same failure.
            logger.warn(
                "Theme '{}' has no valid license ({}); falling back to '{}'.",
                requested, e.reason.publicId, DEFAULT_THEME_ID
            )

            configManager.config.currentTheme = DEFAULT_THEME_ID

            try {
                configManager.saveConfig()
            } catch (t: Throwable) {
                logger.error("Failed to persist the theme fallback: {}", t.message, t)
            }

            uiManager.startUI(DEFAULT_THEME_ID)
            uiManager.activateThemeUI(router, DEFAULT_THEME_ID)

            return StartResult(
                themeId = DEFAULT_THEME_ID,
                alreadyRunning = false,
                fellBackFrom = requested,
                licenseDeniedReason = e.reason.publicId,
                message = e.message
            )
        }

        uiManager.activateThemeUI(router, uiManager.activeTheme)

        return StartResult(themeId = uiManager.activeTheme, alreadyRunning = false)
    }

    /**
     * Stops the theme process and unbinds its route, and reports the theme that was stopped.
     *
     * Null means there was nothing running, which callers treat as success: "the theme is not
     * serving" is the state being asked for either way.
     */
    fun stop(): String? {
        if (!isRunning) {
            return null
        }

        val stopped = uiManager.activeTheme

        uiManager.stopUI(stopped)
        uiManager.disableUIOnRoute(router, Route.Type.THEME_UI)

        return stopped
    }
}
