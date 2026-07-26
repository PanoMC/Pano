package com.panomc.platform.license

import com.panomc.platform.PanoApiManager
import io.vertx.core.Vertx
import io.vertx.kotlin.coroutines.dispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * Entitlement source backed by panomc.com.
 *
 * Reads are synchronous because plugins call [EntitlementManager.hasTier] from ordinary code, so
 * this only ever serves a cached snapshot. Filling that cache is a background fetch, kicked off by
 * [refresh] — at plugin start and whenever the operator uses the panel's "refresh packages" action
 * after buying one.
 *
 * A plugin with nothing cached simply sees "nothing owned", which is the safe default: a freemium
 * plugin keeps running with its paid features locked.
 */
class StoreEntitlementResolver(
    private val panoApiManager: PanoApiManager,
    private val vertx: Vertx
) : EntitlementResolver {
    private val logger = LoggerFactory.getLogger(StoreEntitlementResolver::class.java)

    private val cache = ConcurrentHashMap<String, EntitlementSnapshot>()

    override fun snapshot(pluginId: String): EntitlementSnapshot =
        cache[pluginId] ?: EntitlementSnapshot()

    /** Fire-and-forget refresh; [awaitRefresh] is the variant the panel endpoint waits on. */
    fun refresh(pluginId: String) {
        CoroutineScope(vertx.dispatcher()).launch {
            try {
                awaitRefresh(pluginId)
            } catch (e: Exception) {
                logger.warn("Entitlement refresh failed for '{}': {}", pluginId, e.message)
            }
        }
    }

    /**
     * Re-reads entitlements and replaces the cached snapshot.
     *
     * @return the snapshot now in effect.
     * @throws com.panomc.platform.error.PanoNotConnected when no Pano account is connected.
     */
    suspend fun awaitRefresh(pluginId: String): EntitlementSnapshot {
        // The store resource id matches the plugin id, same assumption as the install flow.
        val (activeTierId, activeTierLevel, tierLevels) = panoApiManager.getEntitlements(pluginId)

        val snapshot = EntitlementSnapshot(
            activeTierId = activeTierId,
            activeTierLevel = activeTierLevel,
            tierLevels = tierLevels
        )

        cache[pluginId] = snapshot

        return snapshot
    }

    fun forget(pluginId: String) {
        cache.remove(pluginId)
    }
}
