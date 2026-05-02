package com.panomc.platform.token

import com.panomc.platform.util.TextUtil.convertToSnakeCase

/**
 * Interface for defining token types. Plugins implement this and register instances with
 * [TokenTypeRegistry.registerPluginToken] so the host removes them when the plugin unloads.
 *
 * On the JVM this is a real `interface` (not a final class), so plugin objects
 * that implement it work with kapt/annotation processing; use a `pano` compile
 * dependency that matches this API, or the monorepo Pano project (`bootstrap = true`).
 *
 * The [getName] method derives the token name from the class name by default:
 *   - Strips "TokenType" suffix
 *   - Converts camelCase to UPPER_SNAKE_CASE
 *   - Example: `MagicLoginTokenType` → `MAGIC_LOGIN`
 */
interface TokenType {
    /**
     * Returns the unique name for this token type, used for DB storage and JWT claims.
     * Default implementation derives from class name: strips "TokenType" suffix and converts to UPPER_SNAKE_CASE.
     */
    fun getName(): String {
        return javaClass.simpleName
            .replace("TokenType", "")
            .convertToSnakeCase()
            .uppercase()
    }

    /**
     * Returns the expiration timestamp (epoch millis) for a new token of this type,
     * calculated from now.
     */
    fun getExpireDate(): Long
}

/**
 * Marker for built-in token types supplied as Spring `@Component` beans.
 * The host injects `List<CoreTokenType>` and registers them in [TokenTypeRegistry] before Gson is configured.
 */
interface CoreTokenType : TokenType