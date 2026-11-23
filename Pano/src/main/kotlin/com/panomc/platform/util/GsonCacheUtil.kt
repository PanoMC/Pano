package com.panomc.platform.util

import com.google.gson.Gson
import org.slf4j.LoggerFactory

/**
 * A [Gson] keeps every type adapter it built in `typeTokenCache`, keyed by the type, with strong references. Core's
 * shared Gson (`DBEntity.gson`, which plugins' database models go through) so kept the model classes of an unloaded
 * plugin and with them its class loader (seen in a heap dump after market updates, 2026-11-23). The cache refills on
 * demand, so emptying it costs a few rebuilt adapters.
 */
object GsonCacheUtil {
    private val log = LoggerFactory.getLogger(GsonCacheUtil::class.java)

    /** Empties [gson]'s adapter cache. Never throws. */
    fun forgetCachedTypes(gson: Gson) {
        try {
            val field = Gson::class.java.getDeclaredField("typeTokenCache")
            field.isAccessible = true
            (field.get(gson) as? MutableMap<*, *>)?.clear()
        } catch (e: Exception) {
            log.debug("Could not empty the Gson type adapter cache: {}", e.toString())
        }
    }
}
