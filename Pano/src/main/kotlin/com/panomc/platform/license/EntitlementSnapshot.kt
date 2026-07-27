package com.panomc.platform.license

/**
 * What a platform currently owns for one freemium plugin, as reported by the store.
 *
 * The store (panomc.com) is the authority for the tier catalogue — plugins do not declare their
 * own packages. The host only caches this snapshot so [EntitlementManager.hasTier] can answer
 * "does this platform have at least tier X?" without a round trip per call.
 *
 * @param activeTierId id of the owned tier, or null when nothing is owned.
 * @param activeTierLevel level of [activeTierId]; 0 when nothing is owned.
 * @param tierLevels the resource's whole catalogue as `tier id -> level`, needed to resolve
 *   "at least this tier" for ids the platform does not own.
 *
 * Billing period (one-time today, monthly/yearly later) deliberately does not appear here: this
 * type only answers *what is owned right now*, so adding subscriptions later does not change the
 * plugin-facing API.
 */
data class EntitlementSnapshot(
    val activeTierId: String? = null,
    val activeTierLevel: Int = 0,
    val tierLevels: Map<String, Int> = emptyMap()
)
