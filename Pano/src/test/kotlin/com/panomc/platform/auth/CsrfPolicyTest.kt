package com.panomc.platform.auth

import com.panomc.platform.AppConstants
import io.vertx.core.http.HttpMethod
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CsrfPolicyTest {
    private val jwtCookie = AppConstants.COOKIE_PREFIX + AppConstants.JWT_COOKIE_NAME
    private val csrfCookie = AppConstants.COOKIE_PREFIX + AppConstants.CSRF_TOKEN_COOKIE_NAME
    private val validTokens = setOf("good-token")

    private fun safe(
        method: HttpMethod = HttpMethod.POST,
        cookie: String? = null,
        authorization: String? = null,
        csrfHeader: String? = null
    ) = AuthProvider.isCsrfSafe(method, cookie, authorization, csrfHeader) { it in validTokens }

    @Test
    fun `safe methods never need a csrf token`() {
        assertTrue(safe(HttpMethod.GET, cookie = "$jwtCookie=abc"))
        assertTrue(safe(HttpMethod.HEAD, cookie = "$jwtCookie=abc"))
        assertTrue(safe(HttpMethod.OPTIONS, cookie = "$jwtCookie=abc"))
    }

    @Test
    fun `cookie session without a csrf header is refused`() {
        assertFalse(safe(cookie = "$jwtCookie=abc; $csrfCookie=tok"))
    }

    @Test
    fun `cookie session with a matching csrf header passes`() {
        assertTrue(safe(cookie = "$jwtCookie=abc; $csrfCookie=tok", csrfHeader = "tok"))
    }

    @Test
    fun `cookie session with a wrong csrf header is refused`() {
        assertFalse(safe(cookie = "$jwtCookie=abc; $csrfCookie=tok", csrfHeader = "other"))
    }

    @Test
    fun `any Authorization header no longer exempts a cookie session`() {
        // The exploit: a cross-site request cannot set Authorization, but the old rule only asked for
        // the header to exist. The session here came from the cookie, so the check must still run.
        assertFalse(safe(cookie = "$jwtCookie=abc; $csrfCookie=tok", authorization = "Basic x"))
        assertFalse(safe(cookie = "$jwtCookie=abc; $csrfCookie=tok", authorization = "Bearer good-token"))
        assertFalse(safe(cookie = "$jwtCookie=abc", authorization = "x"))
    }

    @Test
    fun `a valid Bearer token without a session cookie is exempt`() {
        assertTrue(safe(authorization = "Bearer good-token"))
        assertTrue(safe(authorization = "Bearer good-token", cookie = "theme=dark"))
    }

    @Test
    fun `an invalid or malformed Bearer token is not exempt`() {
        assertFalse(safe(authorization = "Bearer bad-token"))
        assertFalse(safe(authorization = "Bearer "))
        assertFalse(safe(authorization = "Basic good-token"))
        assertFalse(safe(authorization = "good-token"))
        assertFalse(safe(authorization = "Bearer good-token Bearer good-token"))
    }

    @Test
    fun `no credentials at all is refused on a mutation`() {
        assertFalse(safe())
    }

    @Test
    fun `insecure cookie variant is recognised as a session cookie`() {
        assertFalse(safe(cookie = "${jwtCookie}_http=abc", authorization = "Bearer good-token"))
        assertTrue(safe(cookie = "${jwtCookie}_http=abc; ${csrfCookie}_http=tok", csrfHeader = "tok"))
    }

    @Test
    fun `bearerToken extraction matches the session lookup`() {
        assertEquals("good-token", AuthProvider.bearerToken("Bearer good-token"))
        assertEquals(null, AuthProvider.bearerToken(null))
        assertEquals(null, AuthProvider.bearerToken("Token abc"))
    }
}
