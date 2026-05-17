package com.panomc.platform.license

import com.auth0.jwt.JWT
import com.auth0.jwt.interfaces.DecodedJWT
import com.panomc.platform.PanoPluginDescriptor
import com.panomc.platform.PanoPluginWrapper
import com.panomc.platform.PluginManager
import com.panomc.platform.api.PanoPlugin
import com.panomc.platform.config.ConfigManager
import com.panomc.platform.error.PanoNotConnected
import io.vertx.core.Vertx
import io.vertx.kotlin.coroutines.dispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.pf4j.PluginState
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Lazy
import org.springframework.context.annotation.Scope
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Fetches and caches plugin license JWTs from panomc.com via [com.panomc.platform.PanoApiManager].
 *
 * The open-source host does **not** embed or fetch the RS256 public key and does **not**
 * cryptographically verify JWTs — that would duplicate work and mislead operators into thinking
 * the host is an enforcement boundary. **Premium plugins** verify signatures with their own
 * embedded key ([SignedLicense.verifySignature]) and refuse to run when verification fails.
 *
 * Here we only parse the JWT payload for basic consistency checks (claims vs requested resource,
 * jar hash, platform id) and for panel/cache UX; those checks assume the token returned over TLS
 * from the license API is authentic. Forged tokens are rejected when plugins verify.
 *
 * Pano always finishes startup; an unlicensed premium plugin fails its own startup check and is
 * reported in the panel.
 *
 * Once started, [init] schedules a periodic renewal sweep that re-fetches JWTs around their
 * half-life so plugins never observe an expired token, and force-disables any plugin whose
 * license has lapsed and cannot be renewed (e.g. purchase refunded, account disconnected).
 */
@Component
@Lazy
@Scope(value = ConfigurableBeanFactory.SCOPE_SINGLETON)
class LicenseManager(
    private val configManager: ConfigManager,
    private val applicationContext: ApplicationContext,
    private val vertx: Vertx
) {
    private val logger: Logger = LoggerFactory.getLogger(LicenseManager::class.java)

    /** In-memory cache keyed by pluginId. */
    private val cache = ConcurrentHashMap<String, SignedLicense>()

    /** Per-plugin failure capture surfaced to the panel UI. */
    private val failures = ConcurrentHashMap<String, PluginLicenseFailure>()

    /**
     * Plugin IDs that have invoked [requireLicense] this JVM lifetime.
     * Used to re-issue JWTs after the operator connects a Pano account (without restarting plugins).
     */
    private val drmPluginIds = ConcurrentHashMap.newKeySet<String>()

    // --- Theme DRM (parallel state to plugin DRM) -------------------------------------
    //
    // Themes are not PF4J plugins, they're external bun processes spawned by [com.panomc.platform.UIManager].
    // The host fetches a JWT before starting the theme process, hands it to the theme via the
    // PANO_LICENSE_JWT env var, and the theme verifies the RS256 signature with its own
    // embedded public key. If renewal fails for the active premium theme, the host
    // force-falls back to the bundled vanilla theme (premium can't keep serving without a JWT).
    //
    // Only the *active* premium theme is periodically renewed — installed-but-inactive premium
    // themes are dormant (no process) and don't need a license at rest.

    /** Cached license JWT per theme id (set after a successful [requireThemeLicense]). */
    private val themeCache = ConcurrentHashMap<String, SignedLicense>()

    /** Most recent license failure per theme id, surfaced via the panel. */
    private val themeFailures = ConcurrentHashMap<String, ThemeLicenseFailure>()

    /** Theme ids that participated in DRM flow this JVM session (license attempted ≥ 1 time). */
    private val drmThemeIds = ConcurrentHashMap.newKeySet<String>()

    /**
     * Active premium theme bookkeeping. When non-null, the renewal sweep keeps this theme's
     * JWT fresh; when null, no theme-side renewal happens. Set by [com.panomc.platform.UIManager]
     * via [setActivePremiumTheme] right after a successful [requireThemeLicense] + process start.
     */
    private val activePremiumTheme = AtomicReference<ActiveThemeRef?>(null)

    data class ActiveThemeRef(val themeId: String, val version: String, val zipHash: String)

    /** Lazy because PanoApiManager is itself lazy and we want to break startup cycles. */
    private val panoApiManager by lazy {
        applicationContext.getBean(com.panomc.platform.PanoApiManager::class.java)
    }

    private val pluginManager by lazy {
        applicationContext.getBean(PluginManager::class.java)
    }

    /**
     * UIManager is the consumer for theme-license renewal callbacks. Fetched lazily because
     * UIManager itself depends on this manager via ApplicationContext at runtime; constructor
     * injection would create a circular bean dependency at startup.
     */
    private val uiManager by lazy {
        applicationContext.getBean(com.panomc.platform.UIManager::class.java)
    }

    private val renewalRunning = AtomicBoolean(false)
    private var renewalTimerId: Long? = null

    companion object {
        /** How often the renewal sweep runs. One minute keeps things responsive without spamming the API. */
        private const val RENEWAL_CHECK_INTERVAL_MS = 60_000L

        /** Refresh fallback when the JWT did not include `iat`: refresh this many ms before `exp`. */
        private const val REFRESH_FALLBACK_MARGIN_MS = 5 * 60 * 1000L
    }


    /**
     * Fetches a license JWT from panomc.com (TLS), parses claims without cryptographic verification,
     * runs basic consistency checks, caches the result for the panel, and returns [SignedLicense].
     * Premium plugins **must** call [SignedLicense.verifySignature] before trusting the token.
     *
     * Throws [LicenseRequiredException] on HTTP errors, malformed JWT, or claim mismatch.
     */
    suspend fun requireLicense(
        plugin: PanoPlugin,
        resourceId: String,
        version: String
    ): SignedLicense {
        val pluginId = plugin.pluginId
        drmPluginIds.add(pluginId)

        cache[pluginId]?.let { cached ->
            if (!cached.claims.isExpired() &&
                cached.claims.resourceId.equals(resourceId, ignoreCase = true) &&
                cached.claims.version == version
            ) {
                return cached
            }
            cache.remove(pluginId)
        }

        val apiUrl = runCatching { configManager.config.panoApiUrl }.getOrNull() ?: "(unknown)"
        logger.info(
            "Fetching license token for premium plugin '{}' (resource={}, version={}) from {}",
            pluginId, resourceId, version, apiUrl
        )

        return try {
            fetchAndCacheLicense(plugin, resourceId, version)
        } catch (e: LicenseRequiredException) {
            failures[pluginId] = PluginLicenseFailure(
                pluginId = pluginId,
                resourceId = resourceId,
                version = version,
                reason = e.reason,
                message = e.message
            )
            logger.warn(
                "License denied for plugin '{}' (resource={}, version={}): reason={} detail={}",
                pluginId, resourceId, version, e.reason.publicId, e.message ?: "-"
            )
            throw e
        }
    }

    /** Returns the in-memory cached license for a plugin id, or null. */
    fun getCachedLicense(pluginId: String): SignedLicense? = cache[pluginId]

    // ---------- Theme DRM (parallel to the plugin DRM above) -------------------------

    /**
     * Fetches a license JWT from panomc.com for a premium theme and caches it. Behaves like
     * [requireLicense] but for themes: the resource type is implicit (theme), the file hash is
     * the SHA-256 of the installed theme's zip ([com.panomc.platform.UIManager.Companion.InstalledTheme.hash]).
     *
     * The host parses claims for consistency cross-checks; the theme's bun process performs the
     * actual RS256 signature verification with its embedded panomc.com public key (defense in depth).
     *
     * Throws [LicenseRequiredException] on HTTP errors, malformed JWT, claim mismatch, or
     * Pano-not-connected.
     */
    suspend fun requireThemeLicense(
        themeId: String,
        version: String,
        zipHash: String
    ): SignedLicense {
        drmThemeIds.add(themeId)

        themeCache[themeId]?.let { cached ->
            if (!cached.claims.isExpired() &&
                cached.claims.resourceId.equals(themeId, ignoreCase = true) &&
                cached.claims.version == version &&
                cached.claims.jarSha256.equals(zipHash, ignoreCase = true)
            ) {
                return cached
            }
            themeCache.remove(themeId)
        }

        val apiUrl = runCatching { configManager.config.panoApiUrl }.getOrNull() ?: "(unknown)"
        logger.info(
            "Fetching license token for premium theme '{}' (version={}) from {}",
            themeId, version, apiUrl
        )

        return try {
            fetchAndCacheThemeLicense(themeId, version, zipHash)
        } catch (e: LicenseRequiredException) {
            themeFailures[themeId] = ThemeLicenseFailure(
                themeId = themeId,
                version = version,
                reason = e.reason,
                message = e.message
            )
            logger.warn(
                "License denied for theme '{}' (version={}): reason={} detail={}",
                themeId, version, e.reason.publicId, e.message ?: "-"
            )
            throw e
        }
    }

    /** Returns the in-memory cached license for a theme id, or null. */
    fun getCachedThemeLicense(themeId: String): SignedLicense? = themeCache[themeId]

    /** Snapshot of all theme license failures observed in this Pano session. */
    fun getThemeFailures(): List<ThemeLicenseFailure> = themeFailures.values.toList()

    /** Most recent license failure recorded for [themeId], or null. */
    fun getThemeFailure(themeId: String): ThemeLicenseFailure? = themeFailures[themeId]

    /** Clear the recorded failure for a theme (e.g. after a successful retry / reinstall). */
    fun clearThemeFailure(themeId: String) {
        themeFailures.remove(themeId)
    }

    /**
     * True if [themeId] has ever participated in DRM flow this JVM session (i.e. license
     * fetch was attempted at least once).
     */
    fun hasSeenThemeLicenseRequirement(themeId: String): Boolean = drmThemeIds.contains(themeId)

    /**
     * Marks [themeId] as the currently active premium theme so the renewal sweep can keep its
     * JWT fresh. Call this *after* a successful [requireThemeLicense] AND after the theme
     * process has been started by UIManager.
     */
    fun setActivePremiumTheme(themeId: String, version: String, zipHash: String) {
        activePremiumTheme.set(ActiveThemeRef(themeId, version, zipHash))
    }

    /**
     * Clears the active-premium-theme bookkeeping when the active theme changes to a free
     * theme (or another premium theme via [setActivePremiumTheme]). Safe to call with a stale
     * [themeId] — no-op when it does not match what's currently active.
     */
    fun clearActivePremiumTheme(themeId: String? = null) {
        if (themeId == null) {
            activePremiumTheme.set(null)
            return
        }
        activePremiumTheme.updateAndGet { current ->
            if (current == null || current.themeId.equals(themeId, ignoreCase = true)) null else current
        }
    }

    /**
     * Returns the snapshot active premium theme tracked for renewal, or null when the active
     * theme is free (or no theme is started).
     */
    fun getActivePremiumTheme(): ActiveThemeRef? = activePremiumTheme.get()

    /**
     * Best-effort pass over every installed premium theme: fetches a license JWT for each
     * (populating [themeCache] on success, [themeFailures] on denial) so the panel UI can
     * show an accurate licensed/unlicensed badge BEFORE the operator tries to activate.
     *
     * Called once after boot (when `UIManager.installedThemeList` has been populated) and
     * also periodically by the renewal sweep so inactive premium themes don't permanently
     * drift to "unknown" once their initial JWT expires.
     *
     * Silently skipped when no panomc.com account is connected — every theme would just
     * report `NOT_CONNECTED` and spam the logs.
     */
    suspend fun verifyAllInstalledPremiumThemesBestEffort() {
        if (!panoApiManager.isConnected()) return
        val premiumThemes = uiManager.installedThemeList.filter { it.premium }
        if (premiumThemes.isEmpty()) return

        logger.info("Verifying licenses for {} installed premium theme(s)", premiumThemes.size)
        for (theme in premiumThemes) {
            // Skip themes whose cache is already populated and not yet near expiry — the
            // active-theme renewal path keeps that one fresh on its own.
            val cached = themeCache[theme.id]
            if (cached != null && !cached.claims.isExpired()) continue
            try {
                val normalizedVersion = theme.version.removePrefix("v")
                requireThemeLicense(theme.id, normalizedVersion, theme.hash.lowercase())
            } catch (_: LicenseRequiredException) {
                // Recorded on [themeFailures]; panel will surface it.
            } catch (t: Throwable) {
                logger.debug(
                    "Best-effort license verify failed for premium theme '{}': {}",
                    theme.id, t.message,
                )
            }
        }
    }

    /**
     * True if this JVM session has seen this plugin participate in host DRM flow
     * ([requireLicense] or [recordFailure]). Survives clearing cache/failures on Pano disconnect.
     */
    fun hasSeenLicenseRequirement(pluginId: String): Boolean = drmPluginIds.contains(pluginId)

    // ---------- Failure tracking (host post-startup) ---------------------------------

    /** Records a failure observed during plugin start (called from [PluginManager]). */
    internal fun recordFailure(pluginId: String, ex: LicenseRequiredException) {
        drmPluginIds.add(pluginId)
        // Drop any host-cached JWT when the plugin rejects the license (e.g. RS256 verify).
        cache.remove(pluginId)
        failures[pluginId] = PluginLicenseFailure(
            pluginId = pluginId,
            resourceId = null,
            version = null,
            reason = ex.reason,
            message = ex.message
        )
    }

    /** Snapshot of all license failures observed in this Pano session. */
    fun getFailures(): List<PluginLicenseFailure> = failures.values.toList()

    /** Clears the recorded failure for a plugin (called when plugin is uninstalled/reloaded). */
    fun clearFailure(pluginId: String) {
        failures.remove(pluginId)
    }

    /**
     * Drops cached JWTs and failure bookkeeping when the Pano account link is removed, then stops
     * any **STARTED** plugins that participate in DRM ([drmPluginIds]) so they cannot keep running
     * without a host-issued license (dependents are handled first, same order as panel disable).
     *
     * Also force-falls back the active premium theme (if any) to the bundled vanilla theme so a
     * disconnected install never keeps serving a premium theme it no longer has a license for.
     */
    suspend fun clearHostLicenseStateBecausePanoDisconnected() {
        cache.clear()
        failures.clear()
        logger.info("Cleared plugin license cache and failures (Pano account disconnected)")
        stopStartedDrmPluginsAfterHostLicenseRemoved()

        themeCache.clear()
        themeFailures.clear()
        logger.info("Cleared theme license cache and failures (Pano account disconnected)")
        fallbackActivePremiumThemeBecauseLicenseLost(
            reason = LicenseDeniedReason.NOT_CONNECTED,
            message = "Pano account disconnected — premium theme license revoked"
        )
    }

    /**
     * If a premium theme is currently active, force the host back to the bundled vanilla theme
     * because the license can no longer be renewed. UIManager handles stopping the bun process,
     * swapping the proxy route, and persisting `current-theme` in config. No-op when the active
     * theme is free or unset.
     *
     * suspend because UIManager.fallbackToDefaultThemeBecausePremiumLicenseLost has to be
     * suspend too — the eventloop dispatcher would deadlock on a runBlocking wrap.
     */
    private suspend fun fallbackActivePremiumThemeBecauseLicenseLost(
        reason: LicenseDeniedReason,
        message: String?
    ) {
        val ref = activePremiumTheme.getAndSet(null) ?: return
        themeFailures[ref.themeId] = ThemeLicenseFailure(
            themeId = ref.themeId,
            version = ref.version,
            reason = reason,
            message = message
        )
        try {
            uiManager.fallbackToDefaultThemeBecausePremiumLicenseLost(ref.themeId)
        } catch (t: Throwable) {
            logger.error(
                "Failed to fall back to default theme after license loss for premium theme '{}': {}",
                ref.themeId,
                t.message,
                t,
            )
        }
    }

    /**
     * Disables STARTED DRM-tracked plugins (dependents first), matching panel plugin-disable behavior.
     */
    private fun stopStartedDrmPluginsAfterHostLicenseRemoved() {
        for (pluginId in drmPluginIds.toList()) {
            try {
                val wrapper = pluginManager.getPlugin(pluginId) ?: continue
                if (wrapper.pluginState != PluginState.STARTED) {
                    continue
                }
                stopPluginSubtreeLikePanel(pluginId)
            } catch (t: Throwable) {
                logger.error(
                    "Failed to stop DRM plugin '{}' after Pano disconnect: {}",
                    pluginId,
                    t.message,
                    t,
                )
            }
        }
    }

    /**
     * Recursively stops plugins that declare a non-optional dependency on [pluginId], then stops
     * and disables [pluginId]. Matches panel semantics so PF4J dependency order stays consistent.
     */
    private fun stopPluginSubtreeLikePanel(pluginId: String) {
        val dependents = pluginManager.plugins.filter {
            it.pluginState != PluginState.DISABLED &&
                it.descriptor.dependencies.any { dep ->
                    dep.pluginId == pluginId && !dep.isOptional
                }
        }.map { it.pluginId }

        dependents.forEach { dependentId ->
            stopPluginSubtreeLikePanel(dependentId)
        }

        val wrapper = pluginManager.getPlugin(pluginId) ?: return
        if (wrapper.pluginState == PluginState.DISABLED) {
            return
        }

        if (wrapper.pluginState == PluginState.STARTED) {
            pluginManager.stopPlugin(pluginId)
        }
        pluginManager.disablePlugin(pluginId)

        if (drmPluginIds.contains(pluginId)) {
            logger.warn(
                "Stopped and disabled DRM plugin '{}' — host license removed (Pano disconnected)",
                pluginId,
            )
        } else {
            logger.info(
                "Stopped dependent plugin '{}' — required because a DRM plugin above it was revoked",
                pluginId,
            )
        }
    }

    /**
     * After a successful platform connect, try to refresh JWTs for every plugin that has ever
     * requested a license this session (same heuristic as the panel refresh endpoint), plus the
     * currently active premium theme (so a server that just connected can keep serving a
     * premium theme it previously rejected for "not-connected").
     */
    suspend fun refreshLicensesAfterPanoConnectedBestEffort() {
        if (!panoApiManager.isConnected()) {
            return
        }
        val ids = drmPluginIds.toList()
        if (ids.isNotEmpty()) {
            logger.info("Re-fetching licenses for {} plugin(s) after Pano account connected", ids.size)
            for (pluginId in ids) {
                val wrapper = pluginManager.getPlugin(pluginId) as? PanoPluginWrapper ?: continue
                val panoPlugin = wrapper.plugin as? PanoPlugin ?: continue
                val descriptor = wrapper.descriptor as? PanoPluginDescriptor ?: continue
                try {
                    requireLicense(panoPlugin, pluginId, descriptor.version)
                } catch (_: LicenseRequiredException) {
                    // Already recorded on [failures]
                } catch (t: Throwable) {
                    logger.debug(
                        "License refresh after Pano connect skipped for '{}': {}",
                        pluginId,
                        t.message,
                    )
                }
            }
        }

        val themeRef = activePremiumTheme.get()
        if (themeRef != null) {
            logger.info("Re-fetching license for active premium theme '{}' after Pano account connected", themeRef.themeId)
            try {
                requireThemeLicense(themeRef.themeId, themeRef.version, themeRef.zipHash)
                // Success: clear any stale failure so the panel shows "ok".
                themeFailures.remove(themeRef.themeId)
            } catch (_: LicenseRequiredException) {
                // Already recorded on [themeFailures]; the renewal sweep will deal with fallback.
            } catch (t: Throwable) {
                logger.debug(
                    "Theme license refresh after Pano connect skipped for '{}': {}",
                    themeRef.themeId,
                    t.message,
                )
            }
        }
    }

    // ---------- Periodic renewal -----------------------------------------------------

    /**
     * Boots the periodic license renewal sweep. Idempotent.
     *
     * Pano core invokes this once after plugins have started so cached JWTs (default
     * TTL is short — ~1h) are renewed in the background and any plugin whose license
     * cannot be renewed past expiry gets force-disabled. Without this, premium plugins
     * would either continue running with an expired in-memory token (signature
     * verification eventually fails inside the plugin) or simply stop working silently
     * after the first JWT lapses.
     */
    fun init() {
        if (renewalTimerId != null) {
            return
        }
        startLicenseRenewalChecker()
    }

    private fun startLicenseRenewalChecker() {
        logger.info(
            "Started license renewal checker (intervalMs={}).",
            RENEWAL_CHECK_INTERVAL_MS,
        )
        renewalTimerId = vertx.setPeriodic(RENEWAL_CHECK_INTERVAL_MS) {
            // Skip overlapping ticks if a previous sweep is still running (slow API, many plugins).
            if (!renewalRunning.compareAndSet(false, true)) {
                return@setPeriodic
            }
            CoroutineScope(vertx.dispatcher()).launch {
                try {
                    runRenewalTick()
                } catch (t: Throwable) {
                    logger.warn("License renewal sweep failed: {}", t.message, t)
                } finally {
                    renewalRunning.set(false)
                }
            }
        }
    }

    private suspend fun runRenewalTick() {
        if (drmPluginIds.isNotEmpty()) {
            for (pluginId in drmPluginIds.toList()) {
                try {
                    processPluginRenewal(pluginId)
                } catch (t: Throwable) {
                    logger.warn(
                        "Renewal processing failed for plugin '{}': {}",
                        pluginId,
                        t.message,
                        t,
                    )
                }
            }
        }

        // Active premium theme: needs a fresh JWT so the bun process keeps a valid token.
        val themeRef = activePremiumTheme.get()
        if (themeRef != null) {
            try {
                processActiveThemeRenewal(themeRef)
            } catch (t: Throwable) {
                logger.warn(
                    "Renewal processing failed for theme '{}': {}",
                    themeRef.themeId,
                    t.message,
                    t,
                )
            }
        }

        // Inactive premium themes: re-fetch when their cached JWT has expired (or there's
        // no cache yet) so the panel keeps showing accurate license status long after boot.
        // No process, no fingerprint walk, no fallback handling — just a license refresh.
        try {
            refreshInactivePremiumThemeCachesIfExpired()
        } catch (t: Throwable) {
            logger.debug("Inactive premium theme refresh sweep failed: {}", t.message)
        }
    }

    private suspend fun refreshInactivePremiumThemeCachesIfExpired() {
        if (!panoApiManager.isConnected()) return
        val activeId = activePremiumTheme.get()?.themeId
        for (theme in uiManager.installedThemeList) {
            if (!theme.premium) continue
            if (theme.id.equals(activeId, ignoreCase = true)) continue
            val cached = themeCache[theme.id]
            if (cached != null && !cached.claims.isExpired()) continue
            try {
                val normalizedVersion = theme.version.removePrefix("v")
                requireThemeLicense(theme.id, normalizedVersion, theme.hash.lowercase())
            } catch (_: LicenseRequiredException) {
                // Already on [themeFailures]; panel reflects it.
            } catch (t: Throwable) {
                logger.debug(
                    "Renewal of inactive premium theme '{}' failed: {}",
                    theme.id, t.message,
                )
            }
        }
    }

    private suspend fun processActiveThemeRenewal(themeRef: ActiveThemeRef) {
        val cached = themeCache[themeRef.themeId]
        val failure = themeFailures[themeRef.themeId]
        val now = System.currentTimeMillis()

        val needsRenewal = when {
            cached == null -> failure != null
            cached.claims.isExpired() -> true
            else -> {
                val refreshAt = if (cached.claims.issuedAtMs > 0 &&
                    cached.claims.expiresAtMs > cached.claims.issuedAtMs
                ) {
                    cached.claims.issuedAtMs + (cached.claims.expiresAtMs - cached.claims.issuedAtMs) / 2
                } else {
                    cached.claims.expiresAtMs - REFRESH_FALLBACK_MARGIN_MS
                }
                now >= refreshAt
            }
        }
        if (!needsRenewal) {
            return
        }

        val previousExpiresAt = cached?.claims?.expiresAtMs ?: 0L

        try {
            fetchAndCacheThemeLicense(themeRef.themeId, themeRef.version, themeRef.zipHash)
            themeFailures.remove(themeRef.themeId)
            // Notify UIManager about a successful renewal so it can forward the fresh JWT to
            // the running theme process — themes load env at boot, but on a long-lived process
            // we want them to re-read on a renewal beat. UIManager decides whether a restart
            // is needed (today: skip; the cached license is sufficient because UIManager hands
            // it to the theme at next start, and the theme verifies signature with its own key).
            try {
                uiManager.onActiveThemeLicenseRenewed(themeRef.themeId)
            } catch (_: Throwable) {
            }
        } catch (e: LicenseRequiredException) {
            val mustFallback = cached == null || now >= previousExpiresAt
            if (mustFallback) {
                logger.warn(
                    "License for active premium theme '{}' could not be renewed (reason={}). Falling back to default theme.",
                    themeRef.themeId, e.reason.publicId,
                )
                themeCache.remove(themeRef.themeId)
                themeFailures[themeRef.themeId] = ThemeLicenseFailure(
                    themeId = themeRef.themeId,
                    version = themeRef.version,
                    reason = e.reason,
                    message = e.message,
                )
                fallbackActivePremiumThemeBecauseLicenseLost(
                    reason = e.reason,
                    message = e.message
                )
            } else {
                logger.warn(
                    "License renewal for active premium theme '{}' failed (reason={}); current token still valid until {}. Will retry.",
                    themeRef.themeId, e.reason.publicId, java.time.Instant.ofEpochMilli(previousExpiresAt),
                )
            }
        }
    }

    private suspend fun processPluginRenewal(pluginId: String) {
        val wrapper = pluginManager.getPlugin(pluginId) as? PanoPluginWrapper ?: return
        if (wrapper.pluginState != PluginState.STARTED) {
            // Already disabled / not yet started / failed — nothing to renew here. Operator
            // can manually refresh from the panel after they fix the underlying issue.
            return
        }

        val cached = cache[pluginId]
        val failure = failures[pluginId]
        val now = System.currentTimeMillis()

        // Refresh roughly at half-life. Falls back to a fixed pre-expiry margin if the
        // JWT does not carry an `iat` claim (older issuers, unit test fixtures, etc.).
        val needsRenewal = when {
            // STARTED + no cache => either the plugin's own assertStillLicensed() cleared us
            // after a failed re-fetch, or recordFailure() did (plugin-side verify rejected).
            // Either way the plugin is currently serving with no host-issued JWT; try to
            // recover, and if that fails the plugin is force-disabled below.
            cached == null -> failure != null
            cached.claims.isExpired() -> true
            else -> {
                val refreshAt = if (cached.claims.issuedAtMs > 0 &&
                    cached.claims.expiresAtMs > cached.claims.issuedAtMs
                ) {
                    cached.claims.issuedAtMs + (cached.claims.expiresAtMs - cached.claims.issuedAtMs) / 2
                } else {
                    cached.claims.expiresAtMs - REFRESH_FALLBACK_MARGIN_MS
                }
                now >= refreshAt
            }
        }
        if (!needsRenewal) {
            return
        }

        val panoPlugin = wrapper.plugin as? PanoPlugin ?: return
        val descriptor = wrapper.descriptor as? PanoPluginDescriptor ?: return
        val resourceId = cached?.claims?.resourceId?.ifBlank { pluginId }
            ?: failure?.resourceId?.takeIf { it.isNotBlank() }
            ?: pluginId
        val version = descriptor.version
        val previousExpiresAt = cached?.claims?.expiresAtMs ?: 0L

        try {
            renewLicense(panoPlugin, resourceId, version)
            // Renewal success — clear any stale failure (e.g. a previous transient network blip).
            failures.remove(pluginId)
        } catch (e: LicenseRequiredException) {
            // Disable when the cached token has actually lapsed (or there was no host-cached
            // token at all). In both cases the plugin is currently serving with no verifiable
            // JWT, so leaving it STARTED would be worse than disabling and surfacing the
            // failure in the panel.
            val mustDisable = cached == null || now >= previousExpiresAt
            if (mustDisable) {
                logger.warn(
                    "License for plugin '{}' could not be renewed (reason={}). Disabling.",
                    pluginId, e.reason.publicId,
                )
                cache.remove(pluginId)
                failures[pluginId] = PluginLicenseFailure(
                    pluginId = pluginId,
                    resourceId = resourceId,
                    version = version,
                    reason = e.reason,
                    message = e.message,
                )
                stopPluginSubtreeLikePanel(pluginId)
            } else {
                // Still inside the validity window — log and try again next tick.
                logger.warn(
                    "License renewal for plugin '{}' failed (reason={}); current token still valid until {}. Will retry.",
                    pluginId, e.reason.publicId, java.time.Instant.ofEpochMilli(previousExpiresAt),
                )
            }
        }
    }

    /**
     * Bypasses the cache short-circuit and skips writing a panel failure on error. Used by
     * [processPluginRenewal] so a transient network blip during a proactive renewal does not
     * corrupt the panel UX while the existing cached token is still valid.
     *
     * On API success the cache is overwritten with the fresh JWT (and any prior failure is
     * cleared). On failure the existing cache is preserved untouched and the exception is
     * rethrown so the caller can decide whether to disable.
     */
    private suspend fun renewLicense(
        plugin: PanoPlugin,
        resourceId: String,
        version: String
    ): SignedLicense {
        drmPluginIds.add(plugin.pluginId)
        return fetchAndCacheLicense(plugin, resourceId, version)
    }

    // ---------- Internals ------------------------------------------------------------

    /**
     * Performs the actual API fetch + claim cross-checks and writes the result to [cache].
     * Throws [LicenseRequiredException] on any failure; callers decide whether to record a
     * panel failure.
     */
    private suspend fun fetchAndCacheLicense(
        plugin: PanoPlugin,
        resourceId: String,
        version: String
    ): SignedLicense {
        val pluginId = plugin.pluginId

        val jarSha256 = computeJarSha256(plugin)
            ?: throw LicenseRequiredException(pluginId, LicenseDeniedReason.UNKNOWN, "jar-not-found")

        if (!panoApiManager.isConnected()) {
            val apiUrl = runCatching { configManager.config.panoApiUrl }.getOrNull() ?: "(unknown)"
            logger.warn(
                "Premium plugin '{}' requires a license but no panomc.com account is connected to this Pano. " +
                    "Open the panel, go to Settings → panomc.com, and connect an account that owns this plugin. " +
                    "(target API: {})",
                pluginId, apiUrl
            )
            throw LicenseRequiredException(pluginId, LicenseDeniedReason.NOT_CONNECTED)
        }

        val rawJwt = try {
            panoApiManager.issueLicense(resourceId, version, jarSha256)
        } catch (e: PanoNotConnected) {
            throw LicenseRequiredException(pluginId, LicenseDeniedReason.NOT_CONNECTED, e.message)
        } catch (e: LicenseFetchException) {
            val reason = when (e.statusCode) {
                403 -> LicenseDeniedReason.NO_PURCHASE
                404 -> LicenseDeniedReason.VERSION_MISMATCH
                409 -> LicenseDeniedReason.JAR_HASH_MISMATCH
                else -> LicenseDeniedReason.NETWORK_ERROR
            }
            throw LicenseRequiredException(pluginId, reason, e.message)
        } catch (e: Throwable) {
            throw LicenseRequiredException(pluginId, LicenseDeniedReason.NETWORK_ERROR, e.message)
        }

        val claims = parseLicenseClaimsUnverified(rawJwt)
            ?: throw LicenseRequiredException(
                pluginId,
                LicenseDeniedReason.SIGNATURE_INVALID,
                "malformed license JWT from API"
            )

        if (claims.isExpired()) {
            throw LicenseRequiredException(pluginId, LicenseDeniedReason.EXPIRED)
        }
        if (!claims.resourceId.equals(resourceId, ignoreCase = true)) {
            throw LicenseRequiredException(pluginId, LicenseDeniedReason.AUDIENCE_MISMATCH)
        }
        if (claims.version != version) {
            throw LicenseRequiredException(pluginId, LicenseDeniedReason.VERSION_MISMATCH)
        }
        if (!claims.jarSha256.equals(jarSha256, ignoreCase = true)) {
            throw LicenseRequiredException(pluginId, LicenseDeniedReason.JAR_HASH_MISMATCH)
        }
        val expectedPlatformId = configManager.config.panoAccount.platformId
        if (expectedPlatformId.isNotBlank() && claims.platformId != expectedPlatformId) {
            throw LicenseRequiredException(pluginId, LicenseDeniedReason.PLATFORM_MISMATCH)
        }

        val license = SignedLicense(rawJwt, claims)
        cache[pluginId] = license
        // Recovering from a previous failure for the same plugin (e.g. after a reload).
        failures.remove(pluginId)
        logger.info(
            "License token cached for premium plugin '{}' (resource={}, version={}, expires={}); plugin must verify signature.",
            pluginId, resourceId, version, java.time.Instant.ofEpochMilli(claims.expiresAtMs)
        )
        return license
    }

    /**
     * Theme-side counterpart to [fetchAndCacheLicense]. Same flow (API call, parse claims, cross
     * check audience/version/hash/platform) but writes to [themeCache] and uses theme-flavoured
     * failure types. The theme bun process is responsible for the actual RS256 signature
     * verification with its embedded public key.
     */
    private suspend fun fetchAndCacheThemeLicense(
        themeId: String,
        version: String,
        zipHash: String
    ): SignedLicense {
        if (!panoApiManager.isConnected()) {
            val apiUrl = runCatching { configManager.config.panoApiUrl }.getOrNull() ?: "(unknown)"
            logger.warn(
                "Premium theme '{}' requires a license but no panomc.com account is connected to this Pano. " +
                    "Open the panel, go to Settings → panomc.com, and connect an account that owns this theme. " +
                    "(target API: {})",
                themeId, apiUrl
            )
            throw LicenseRequiredException(themeId, LicenseDeniedReason.NOT_CONNECTED)
        }

        val rawJwt = try {
            panoApiManager.issueLicense(themeId, version, zipHash.lowercase())
        } catch (e: PanoNotConnected) {
            throw LicenseRequiredException(themeId, LicenseDeniedReason.NOT_CONNECTED, e.message)
        } catch (e: LicenseFetchException) {
            val reason = when (e.statusCode) {
                403 -> LicenseDeniedReason.NO_PURCHASE
                404 -> LicenseDeniedReason.VERSION_MISMATCH
                409 -> LicenseDeniedReason.JAR_HASH_MISMATCH
                else -> LicenseDeniedReason.NETWORK_ERROR
            }
            throw LicenseRequiredException(themeId, reason, e.message)
        } catch (e: Throwable) {
            throw LicenseRequiredException(themeId, LicenseDeniedReason.NETWORK_ERROR, e.message)
        }

        val claims = parseLicenseClaimsUnverified(rawJwt)
            ?: throw LicenseRequiredException(
                themeId,
                LicenseDeniedReason.SIGNATURE_INVALID,
                "malformed license JWT from API"
            )

        if (claims.isExpired()) {
            throw LicenseRequiredException(themeId, LicenseDeniedReason.EXPIRED)
        }
        if (!claims.resourceId.equals(themeId, ignoreCase = true)) {
            throw LicenseRequiredException(themeId, LicenseDeniedReason.AUDIENCE_MISMATCH)
        }
        if (claims.version != version) {
            throw LicenseRequiredException(themeId, LicenseDeniedReason.VERSION_MISMATCH)
        }
        if (!claims.jarSha256.equals(zipHash, ignoreCase = true)) {
            throw LicenseRequiredException(themeId, LicenseDeniedReason.JAR_HASH_MISMATCH)
        }
        val expectedPlatformId = configManager.config.panoAccount.platformId
        if (expectedPlatformId.isNotBlank() && claims.platformId != expectedPlatformId) {
            throw LicenseRequiredException(themeId, LicenseDeniedReason.PLATFORM_MISMATCH)
        }

        val license = SignedLicense(rawJwt, claims)
        themeCache[themeId] = license
        themeFailures.remove(themeId)
        logger.info(
            "License token cached for premium theme '{}' (version={}, expires={}); theme process must verify signature.",
            themeId, version, java.time.Instant.ofEpochMilli(claims.expiresAtMs)
        )
        return license
    }

    /**
     * Parses JWT payload without signature verification (host is open-source; crypto boundary is the plugin).
     */
    private fun parseLicenseClaimsUnverified(rawJwt: String): LicenseClaims? {
        val decoded: DecodedJWT =
            try {
                JWT.decode(rawJwt)
            } catch (e: Exception) {
                logger.warn("Could not parse license JWT from API: {}", e.message)
                return null
            }

        val audience = decoded.audience?.firstOrNull() ?: return null
        return LicenseClaims(
            issuer = decoded.issuer ?: configManager.config.resolvedLicenseJwtIssuer(),
            platformId = decoded.subject ?: return null,
            resourceId = audience,
            userId = decoded.getClaim("uid").asString() ?: return null,
            version = decoded.getClaim("ver").asString() ?: return null,
            jarSha256 = decoded.getClaim("hash").asString() ?: return null,
            issuedAtMs = decoded.issuedAtAsInstant?.toEpochMilli() ?: 0L,
            expiresAtMs = decoded.expiresAtAsInstant?.toEpochMilli() ?: 0L,
            keyId = decoded.keyId,
            tokenId = decoded.id
        )
    }

    private fun computeJarSha256(plugin: PanoPlugin): String? {
        val wrapper = pluginManager.getPlugin(plugin.pluginId) as? PanoPluginWrapper ?: return null
        // PanoPluginWrapper.hash is internal so we read it via reflection here. The
        // license module is in the same compilation unit as PanoPluginWrapper, so the
        // visibility check at the JVM level allows direct access; this dereference is
        // simply to be explicit.
        return wrapper.hash.takeIf { it.isNotBlank() }?.lowercase()
    }

    /**
     * Internal exception used by [com.panomc.platform.PanoApiManager.issueLicense] to
     * carry HTTP status codes back to the manager so we can map them to [LicenseDeniedReason].
     */
    class LicenseFetchException(val statusCode: Int, message: String) : RuntimeException(message)
}
