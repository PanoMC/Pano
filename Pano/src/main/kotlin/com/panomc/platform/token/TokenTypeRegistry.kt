package com.panomc.platform.token

import org.slf4j.LoggerFactory

/**
 * Central registry for all token types (both core and plugin-defined).
 * Token types are registered at startup; plugins register theirs via [TokenTypeRegistry.register].
 */
object TokenTypeRegistry {
    private val logger = LoggerFactory.getLogger("TokenTypeRegistry")
    private val types = mutableMapOf<String, TokenType>()

    /**
     * Registers a token type. Logs a warning if a type with the same name already exists.
     */
    fun register(tokenType: TokenType) {
        val name = tokenType.getName()
        if (types.containsKey(name)) {
            logger.warn("TokenType '$name' is already registered, overwriting with ${tokenType.javaClass.name}")
        }
        types[name] = tokenType
        logger.debug("Registered token type: $name")
    }

    /**
     * Looks up a token type by its name. Returns null if not found.
     */
    fun get(name: String): TokenType? = types[name]

    /**
     * Returns all registered token types.
     */
    fun getAll(): List<TokenType> = types.values.toList()

    /**
     * Registers all built-in core token types. Called during platform initialization.
     */
    fun registerCoreTypes() {
        register(AuthenticationTokenType)
        register(ActivationTokenType)
        register(ResetPasswordTokenType)
        register(ServerAuthenticationTokenType)
        register(ChangeEmailTokenType)
        register(RegisterWithLinkCodeTokenType)
        register(SetUsernameTokenType)
    }
}
