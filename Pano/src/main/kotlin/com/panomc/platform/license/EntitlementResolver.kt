package com.panomc.platform.license

/**
 * Source of freemium entitlements for a plugin.
 *
 * Phase 1 ships only [NoEntitlements]: the tier catalogue and ownership live in the store and are
 * rendered by the panel's embedded license view, but nothing is wired back into the host yet, so
 * every plugin sees "nothing owned". Phase 2 swaps in an implementation backed by panomc.com via
 * [EntitlementManager.setResolver] — plugin code does not change.
 */
interface EntitlementResolver {
    fun snapshot(pluginId: String): EntitlementSnapshot
}

/** Default resolver: nothing is ever owned. */
internal object NoEntitlements : EntitlementResolver {
    override fun snapshot(pluginId: String): EntitlementSnapshot = EntitlementSnapshot()
}
