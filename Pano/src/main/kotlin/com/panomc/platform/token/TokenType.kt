package com.panomc.platform.token

import com.panomc.platform.util.TextUtil.convertToSnakeCase

/**
 * Interface for defining token types. Plugins can implement this to register
 * their own custom token types via [TokenEventListener].
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