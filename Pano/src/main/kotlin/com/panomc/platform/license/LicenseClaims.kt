package com.panomc.platform.license

/**
 * Decoded claims from a panomc.com-issued plugin license token. See [LicenseTokenIssuer]
 * on the website backend for the producer side.
 */
data class LicenseClaims(
    /** Issuer (always panomc.com). */
    val issuer: String,
    /** Pano platform id (subject). */
    val platformId: String,
    /** Audience = resourceId (the plugin this license is for). */
    val resourceId: String,
    /** panomc.com user id. */
    val userId: String,
    /** Plugin version this license is valid for. */
    val version: String,
    /** Hex SHA-256 of the legitimate plugin JAR. */
    val jarSha256: String,
    /** Issued-at, epoch milliseconds. */
    val issuedAtMs: Long,
    /** Expiry, epoch milliseconds. */
    val expiresAtMs: Long,
    /** Signing key id from the JOSE header (used to detect rotation). */
    val keyId: String?,
    /** Token id (jti). */
    val tokenId: String?
) {
    fun isExpired(nowMs: Long = System.currentTimeMillis()): Boolean = nowMs >= expiresAtMs
}
