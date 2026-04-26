package com.panomc.platform.api.event

import com.panomc.platform.token.TokenTypeRegistry

/**
 * Event listener for token type registration.
 * Plugins implement this to register their custom [com.panomc.platform.token.TokenType] instances.
 *
 * Example usage in a plugin:
 * ```kotlin
 * @EventListener
 * class MyTokenEventHandler(private val plugin: MyPlugin) : TokenEventListener {
 *     override fun registerTokenTypes(registry: TokenTypeRegistry) {
 *         registry.register(MyCustomTokenType)
 *     }
 * }
 * ```
 */
interface TokenEventListener : PanoEventListener {
    /**
     * Called during platform initialization to allow plugins to register custom token types.
     */
    fun registerTokenTypes(registry: TokenTypeRegistry) {}
}
