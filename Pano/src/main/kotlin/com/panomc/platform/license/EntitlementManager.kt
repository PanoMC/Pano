package com.panomc.platform.license

import com.panomc.platform.PanoApiManager
import io.vertx.core.Vertx
import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.context.annotation.Lazy
import org.springframework.context.annotation.Scope
import org.springframework.stereotype.Component

/**
 * Answers freemium entitlement questions for plugins ("does this platform own tier X?").
 *
 * Counterpart to [LicenseManager]: that one guards **premium** plugins (all-or-nothing DRM), this
 * one serves **freemium** plugins, which always run but keep some features behind a purchased
 * tier. The two are mutually exclusive — see [LicenseManager.requireLicense].
 *
 * Backed by [StoreEntitlementResolver], which caches what panomc.com reports. Nothing pushes a
 * purchase to the platform, so [refresh] is the way out of "bought the package but the plugin still
 * says locked" — the panel exposes it as a refresh action on the addon page.
 */
@Component
@Lazy
@Scope(value = ConfigurableBeanFactory.SCOPE_SINGLETON)
class EntitlementManager(
    private val panoApiManager: PanoApiManager,
    private val vertx: Vertx
) {
    private val storeResolver by lazy { StoreEntitlementResolver(panoApiManager, vertx) }

    @Volatile
    private var resolver: EntitlementResolver? = null

    private fun activeResolver(): EntitlementResolver = resolver ?: storeResolver

    /** Overrides the entitlement source; mainly a seam for tests. */
    fun setResolver(resolver: EntitlementResolver) {
        this.resolver = resolver
    }

    /**
     * Re-reads what this platform owns for the plugin and replaces the cached snapshot.
     *
     * @return the refreshed snapshot.
     * @throws com.panomc.platform.error.PanoNotConnected when no Pano account is connected.
     */
    suspend fun refresh(pluginId: String): EntitlementSnapshot = storeResolver.awaitRefresh(pluginId)

    /** Warms the cache without blocking the caller (used when a freemium plugin starts). */
    fun refreshInBackground(pluginId: String) {
        storeResolver.refresh(pluginId)
    }

    fun forget(pluginId: String) {
        storeResolver.forget(pluginId)
    }

    fun snapshot(pluginId: String): EntitlementSnapshot = activeResolver().snapshot(pluginId)

    /** Id of the tier this platform owns for the plugin, or null when nothing is owned. */
    fun activeTier(pluginId: String): String? = snapshot(pluginId).activeTierId

    /**
     * True when the platform owns **at least** [tierId].
     *
     * Levels drive upgrades: owning Ultra (level 2) also satisfies `hasTier("pro")` (level 1), so a
     * plugin gating a Pro feature does not have to enumerate every higher tier. Unknown ids are
     * always false — a tier the store does not publish cannot be owned.
     */
    fun hasTier(pluginId: String, tierId: String): Boolean {
        val snapshot = snapshot(pluginId)
        val requiredLevel = snapshot.tierLevels[tierId] ?: return false

        return snapshot.activeTierLevel >= requiredLevel
    }
}
