package com.panomc.platform.license

import com.auth0.jwt.JWT
import com.auth0.jwt.interfaces.DecodedJWT
import com.panomc.platform.PanoPluginDescriptor
import com.panomc.platform.PanoPluginWrapper
import com.panomc.platform.PluginManager
import com.panomc.platform.api.PanoPlugin
import com.panomc.platform.config.ConfigManager
import com.panomc.platform.error.PanoNotConnected
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Lazy
import org.springframework.context.annotation.Scope
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap
import org.pf4j.PluginState

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
 */
@Component
@Lazy
@Scope(value = ConfigurableBeanFactory.SCOPE_SINGLETON)
class LicenseManager(
    private val configManager: ConfigManager,
    private val applicationContext: ApplicationContext
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

        val jarSha256 = computeJarSha256(plugin)
            ?: failAndThrow(pluginId, resourceId, version, LicenseDeniedReason.UNKNOWN, "jar-not-found")

        if (!panoApiManager.isConnected()) {
            logger.warn(
                "Premium plugin '{}' requires a license but no panomc.com account is connected to this Pano. " +
                    "Open the panel, go to Settings → panomc.com, and connect an account that owns this plugin. " +
                    "(target API: {})",
                pluginId, apiUrl
            )
            failAndThrow(pluginId, resourceId, version, LicenseDeniedReason.NOT_CONNECTED)
        }

        val rawJwt = try {
            panoApiManager.issueLicense(resourceId, version, jarSha256)
        } catch (e: PanoNotConnected) {
            failAndThrow(pluginId, resourceId, version, LicenseDeniedReason.NOT_CONNECTED, e.message)
        } catch (e: LicenseFetchException) {
            val reason = when (e.statusCode) {
                403 -> LicenseDeniedReason.NO_PURCHASE
                404 -> LicenseDeniedReason.VERSION_MISMATCH
                409 -> LicenseDeniedReason.JAR_HASH_MISMATCH
                else -> LicenseDeniedReason.NETWORK_ERROR
            }
            failAndThrow(pluginId, resourceId, version, reason, e.message)
        } catch (e: Throwable) {
            failAndThrow(pluginId, resourceId, version, LicenseDeniedReason.NETWORK_ERROR, e.message)
        }

        val claims = parseLicenseClaimsUnverified(rawJwt)
            ?: failAndThrow(
                pluginId,
                resourceId,
                version,
                LicenseDeniedReason.SIGNATURE_INVALID,
                "malformed license JWT from API"
            )

        if (claims.isExpired()) {
            failAndThrow(pluginId, resourceId, version, LicenseDeniedReason.EXPIRED)
        }
        if (!claims.resourceId.equals(resourceId, ignoreCase = true)) {
            failAndThrow(pluginId, resourceId, version, LicenseDeniedReason.AUDIENCE_MISMATCH)
        }
        if (claims.version != version) {
            failAndThrow(pluginId, resourceId, version, LicenseDeniedReason.VERSION_MISMATCH)
        }
        if (!claims.jarSha256.equals(jarSha256, ignoreCase = true)) {
            failAndThrow(pluginId, resourceId, version, LicenseDeniedReason.JAR_HASH_MISMATCH)
        }
        val expectedPlatformId = configManager.config.panoAccount.platformId
        if (expectedPlatformId.isNotBlank() && claims.platformId != expectedPlatformId) {
            failAndThrow(pluginId, resourceId, version, LicenseDeniedReason.PLATFORM_MISMATCH)
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

    // ---------- Internals ------------------------------------------------------------

    private fun failAndThrow(
        pluginId: String,
        resourceId: String,
        version: String,
        reason: LicenseDeniedReason,
        detail: String? = null
    ): Nothing {
        val failure = PluginLicenseFailure(
            pluginId = pluginId,
            resourceId = resourceId,
            version = version,
            reason = reason,
            message = detail
        )
        failures[pluginId] = failure
        logger.warn(
            "License denied for plugin '{}' (resource={}, version={}): reason={} detail={}",
            pluginId, resourceId, version, reason.publicId, detail ?: "-"
        )
        throw LicenseRequiredException(pluginId, reason, detail)
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
