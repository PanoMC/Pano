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

    /** Lazy because PanoApiManager is itself lazy and we want to break startup cycles. */
    private val panoApiManager by lazy {
        applicationContext.getBean(com.panomc.platform.PanoApiManager::class.java)
    }

    private val pluginManager by lazy {
        applicationContext.getBean(PluginManager::class.java)
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
     */
    fun clearHostLicenseStateBecausePanoDisconnected() {
        cache.clear()
        failures.clear()
        logger.info("Cleared plugin license cache and failures (Pano account disconnected)")
        stopStartedDrmPluginsAfterHostLicenseRemoved()
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
     * requested a license this session (same heuristic as the panel refresh endpoint).
     */
    suspend fun refreshLicensesAfterPanoConnectedBestEffort() {
        if (!panoApiManager.isConnected()) {
            return
        }
        val ids = drmPluginIds.toList()
        if (ids.isEmpty()) {
            return
        }
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
        if (drmPluginIds.isEmpty()) {
            return
        }

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
