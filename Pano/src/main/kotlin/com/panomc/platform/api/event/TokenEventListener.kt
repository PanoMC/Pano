package com.panomc.platform.api.event

import com.panomc.platform.token.TokenTypeRegistry

/**
 * Optional hook for token-related lifecycle. Prefer registering types from the plugin with
 * [TokenTypeRegistry.registerPluginToken] so the host unregisters them on unload.
 */
interface TokenEventListener : PanoEventListener {
    /**
     * Extension point; the platform does not invoke this automatically today.
     */
    fun registerTokenTypes(registry: TokenTypeRegistry) {}
}
