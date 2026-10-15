package com.panomc.platform.ui

import com.panomc.platform.AppConstants.DEFAULT_THEME_ID
import com.panomc.platform.ApiLevel
import com.panomc.platform.UIManager
import com.panomc.platform.gate.Verdict
import com.panomc.platform.model.Error
import com.panomc.platform.config.ConfigManager
import com.panomc.platform.license.LicenseRequiredException
import com.panomc.platform.model.Route
import io.vertx.ext.web.Router
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.Logger
import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.context.annotation.Lazy
import org.springframework.context.annotation.Scope
import org.springframework.stereotype.Component

/**
 * Starting and stopping the site's front-end, in one place, because several callers need it.
 *
 * It used to live inside the two panel endpoints that did it on purpose — `POST /api/v1/panel/themes`
 * and `DELETE /api/v1/panel/themes`. Switching the usage mode has to do the same thing (SERVERS mode
 * has no website and should not be paying for a second Bun process), and a third copy of "start
 * the theme, and if its licence is refused fall back to vanilla and persist that" is a third place
 * for the fallback to be subtly wrong. Since the front-end modes (doc 05 §8) it is also the single
 * switch between a theme, a custom app, an external upstream and no front-end at all: [apply].
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
     * of reporting a plain success. [themeId] is the id of whatever serves the site: the theme, the
     * custom app, or `"external"` for a proxy or no front-end (see [UIManager.activeFrontendId]).
     */
    data class StartResult(
        val themeId: String,
        val alreadyRunning: Boolean,
        val fellBackFrom: String? = null,
        val licenseDeniedReason: String? = null,
        val message: String? = null,
        val mode: FrontendMode = FrontendMode.THEME
    )

    private val applyLock = Mutex()

    /** Whether a front-end is currently bound to the wildcard route. */
    val isRunning: Boolean get() = uiManager.activatedUIList.containsKey(Route.Type.THEME_UI)

    /** The theme that would be started, which is also the one that is running when one is. */
    val activeTheme: String get() = uiManager.activeTheme

    /**
     * Starts the configured front-end (`frontend.mode`) and binds its route.
     *
     * Idempotent: a front-end that is already bound is reported as such rather than restarted, which
     * is what makes this safe to call from a settings save that may not have changed anything.
     */
    suspend fun start(): StartResult {
        if (isRunning) {
            return StartResult(
                themeId = uiManager.activeFrontendId(),
                alreadyRunning = true,
                mode = uiManager.frontendMode
            )
        }

        val frontend = configManager.config.effectiveFrontend

        return apply(frontend.parsedMode, frontend.customApp, frontend.upstreamUrl)
    }

    /**
     * Makes [mode] the front-end, in the "start the new one, then swap" order of the theme switch: the
     * new process is started and checked first, the choice is persisted, and only then is the old
     * route unbound and its process stopped. A new front-end that cannot start throws
     * [FrontendStartFailed] (or the licence/URL error that explains why) and the previous one is left
     * serving, untouched.
     *
     * [customAppId] is read for [FrontendMode.CUSTOM_APP], [upstreamUrl] for [FrontendMode.EXTERNAL];
     * the other fields of `frontend {}` (`site-url`, `descriptor-url`, `dev-url`) are plain settings
     * the caller writes before calling this. For [FrontendMode.THEME] a `dev-url` that applies (Development
     * Mode on) stands in for the theme process.
     */
    suspend fun apply(mode: FrontendMode, customAppId: String? = null, upstreamUrl: String? = null): StartResult =
        applyLock.withLock {
            when (mode) {
                FrontendMode.THEME -> applyTheme()
                FrontendMode.CUSTOM_APP -> applyCustomApp(customAppId?.trim().orEmpty())
                FrontendMode.EXTERNAL -> applyExternal(upstreamUrl)
                FrontendMode.NONE -> applyNone()
            }
        }

    private suspend fun applyTheme(): StartResult {
        val dev = uiManager.devServerTarget(assumeThemeMode = true)
        val requested = uiManager.activeTheme

        // Last line of the gate for a theme: whatever set the active theme, an incompatible one is never started here.
        requireCompatibleTheme(requested, uiManager.installedThemeList)

        if (dev != null) {
            if (uiManager.isBoundTo(FrontendMode.THEME, UIManager.DEV_SERVER_FRONTEND_ID, dev.origin)) {
                return StartResult(requested, alreadyRunning = true)
            }

            persist(FrontendMode.THEME, customApp = null, upstreamUrl = null)

            swap { uiManager.activateExternalUI(router, dev, UIManager.DEV_SERVER_FRONTEND_ID) }

            return StartResult(requested, alreadyRunning = false)
        }

        if (uiManager.isBoundTo(FrontendMode.THEME, requested, null)) {
            return StartResult(requested, alreadyRunning = true)
        }

        var themeId = requested
        var fellBackFrom: String? = null
        var deniedReason: String? = null
        var message: String? = null

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
                uiManager.startUI(DEFAULT_THEME_ID)
            } catch (t: Throwable) {
                throw startFailed(t)
            }

            themeId = DEFAULT_THEME_ID
            fellBackFrom = requested
            deniedReason = e.reason.publicId
            message = e.message
        } catch (t: Throwable) {
            throw startFailed(t)
        }

        persist(FrontendMode.THEME, customApp = null, upstreamUrl = null) {
            uiManager.stopUI(themeId)
        }

        swap(keepProcess = themeId) { uiManager.activateThemeUI(router, themeId) }

        return StartResult(
            themeId = themeId,
            alreadyRunning = false,
            fellBackFrom = fellBackFrom,
            licenseDeniedReason = deniedReason,
            message = message
        )
    }

    private suspend fun applyCustomApp(id: String): StartResult {
        if (!CustomAppInstaller.ID_PATTERN.matches(id)) {
            throw FrontendStartFailed("No custom app is selected.")
        }

        if (uiManager.isBoundTo(FrontendMode.CUSTOM_APP, id, null)) {
            return StartResult(id, alreadyRunning = true, mode = FrontendMode.CUSTOM_APP)
        }

        // The process of the app being replaced is not stopped before the new one answers.
        try {
            uiManager.startCustomApp(id)
        } catch (t: Throwable) {
            uiManager.stopUI(id)

            throw startFailed(t)
        }

        persist(FrontendMode.CUSTOM_APP, customApp = id, upstreamUrl = null) {
            uiManager.stopUI(id)
        }

        swap(keepProcess = id) { uiManager.activateCustomAppUI(router, id) }

        return StartResult(id, alreadyRunning = false, mode = FrontendMode.CUSTOM_APP)
    }

    private fun applyExternal(upstreamUrl: String?): StartResult {
        val target = UpstreamTarget.parse(upstreamUrl) ?: throw UpstreamInvalidUrl()

        if (target.pointsAtPano(configManager.config)) {
            throw UpstreamIsPano()
        }

        if (uiManager.isBoundTo(FrontendMode.EXTERNAL, UIManager.EXTERNAL_FRONTEND_ID, target.origin)) {
            return StartResult(uiManager.activeFrontendId(), alreadyRunning = true, mode = FrontendMode.EXTERNAL)
        }

        persist(FrontendMode.EXTERNAL, customApp = null, upstreamUrl = target.origin)

        swap { uiManager.activateExternalUI(router, target) }

        return StartResult(uiManager.activeFrontendId(), alreadyRunning = false, mode = FrontendMode.EXTERNAL)
    }

    private fun applyNone(): StartResult {
        if (uiManager.isBoundTo(FrontendMode.NONE, UIManager.EXTERNAL_FRONTEND_ID, null)) {
            return StartResult(uiManager.activeFrontendId(), alreadyRunning = true, mode = FrontendMode.NONE)
        }

        persist(FrontendMode.NONE, customApp = null, upstreamUrl = null)

        swap { uiManager.useNoFrontend() }

        return StartResult(uiManager.activeFrontendId(), alreadyRunning = false, mode = FrontendMode.NONE)
    }

    /**
     * Writes the choice to config.conf. Called after the new process answered and before the old one
     * is touched, so a disk that refuses the write leaves the previous front-end serving ([undo] stops
     * what was just started).
     */
    private fun persist(mode: FrontendMode, customApp: String?, upstreamUrl: String?, undo: () -> Unit = {}) {
        val config = configManager.config
        val frontend = config.effectiveFrontend

        frontend.mode = mode.name

        customApp?.let { frontend.customApp = it }
        upstreamUrl?.let { frontend.upstreamUrl = it }

        config.frontend = frontend

        try {
            configManager.saveConfig()
        } catch (t: Throwable) {
            undo()

            throw FrontendStartFailed("The choice could not be saved: ${t.message}")
        }
    }

    /**
     * Unbinds the old route, stops the old process (unless it is [keepProcess], the one just
     * started) and binds the new route with [bind].
     */
    private fun swap(keepProcess: String? = null, bind: () -> Unit) {
        val old = uiManager.siteBinding
        val oldProcess = old?.takeIf { it.upstream == null }?.id

        uiManager.disableUIOnRoute(router, Route.Type.THEME_UI)

        if (oldProcess != null && oldProcess != keepProcess) {
            uiManager.stopUI(oldProcess)
        }

        bind()
    }

    private fun startFailed(t: Throwable): FrontendStartFailed {
        logger.error("The front-end could not be started: {}", t.message, t)

        return FrontendStartFailed(t.message ?: t.javaClass.simpleName)
    }

    /**
     * Stops the front-end process and unbinds its route, and reports what was stopped.
     *
     * Null means there was nothing running, which callers treat as success: "the site is not
     * serving" is the state being asked for either way.
     */
    fun stop(): String? {
        if (!isRunning) {
            return null
        }

        val stopped = uiManager.activeFrontendId()

        uiManager.stopSiteProcess()
        uiManager.disableUIOnRoute(router, Route.Type.THEME_UI)

        return stopped
    }
}

/**
 * A theme outside the supported API level cannot be activated (doc 04 section 7). The boot gate lets such a theme
 * fall back to the bundled one; the panel and the console refuse the switch with this error instead.
 */
class ThemeApiLevelUnsupported(
    themeId: String = "",
    verdict: String = "TOO_OLD",
    apiLevel: Int = 0,
    min: Int = ApiLevel.MIN_SUPPORTED,
    current: Int = ApiLevel.CURRENT
) : Error(
    "THEME_API_LEVEL_UNSUPPORTED",
    400,
    "",
    mapOf(
        "message" to "The theme '$themeId' was built for API level $apiLevel; this Pano supports $min to $current.",
        "themeId" to themeId,
        "verdict" to verdict,
        "apiLevel" to apiLevel,
        "min" to min,
        "current" to current
    )
)

/**
 * Throws [ThemeApiLevelUnsupported] when the installed theme [id] does not pass the API level gate. The bundled
 * default theme and an id that is not in [installed] pass (the caller reports an unknown theme itself).
 */
fun requireCompatibleTheme(id: String, installed: List<UIManager.Companion.InstalledTheme>) {
    val verdict = UIManager.themeVerdict(id, installed)

    if (verdict != Verdict.OK) {
        throw ThemeApiLevelUnsupported(id, verdict.name, installed.firstOrNull { it.id == id }?.apiLevel ?: 0)
    }
}
