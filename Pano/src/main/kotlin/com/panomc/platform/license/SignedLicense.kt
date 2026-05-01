package com.panomc.platform.license

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.exceptions.JWTVerificationException
import java.security.interfaces.RSAPublicKey

/**
 * A plugin license JWT plus decoded claims from the host.
 *
 * The host parses the JWT **without verifying** the RS256 signature (see [LicenseManager]); only
 * premium plugins enforce authenticity via [verifySignature] using their embedded public key.
 */
data class SignedLicense(
    /** Raw RS256-signed JWT from the license API. */
    val rawJwt: String,
    /** Parsed claims from the host (signature not verified there). */
    val claims: LicenseClaims
) {
    /**
     * Verifies the RS256 signature against the supplied public key and JWT `iss`.
     * Premium plugins call this with their embedded panomc.com key and [com.panomc.platform.api.PanoPlugin.getLicenseJwtIssuer]
     * so issuer matches `licensing.issuer` / dev hosts (e.g. `dev.panomc.com`).
     */
    fun verifySignature(publicKey: RSAPublicKey, expectedIssuer: String = "panomc.com"): Boolean = try {
        val algorithm = Algorithm.RSA256(publicKey, null)
        JWT.require(algorithm).withIssuer(expectedIssuer).build().verify(rawJwt)
        true
    } catch (_: JWTVerificationException) {
        false
    } catch (_: Exception) {
        false
    }
}
