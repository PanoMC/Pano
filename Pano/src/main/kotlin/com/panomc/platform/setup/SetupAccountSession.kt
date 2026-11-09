package com.panomc.platform.setup

import com.panomc.platform.backup.remote.PanoHostException
import com.panomc.platform.error.PanoHostError
import io.vertx.core.http.Cookie
import io.vertx.core.http.CookieSameSite
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.RoutingContext
import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.context.annotation.Lazy
import org.springframework.context.annotation.Scope
import org.springframework.stereotype.Component
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * Ties the panomc.com account connected during setup to the browser that connected it.
 *
 * The setup wizard has no login, so without this anyone who could reach an unfinished install could
 * use the account the installer connected (list its backups and Pano Host instances, move one here).
 * A successful connect hands that browser an unguessable cookie; every setup route that acts on the
 * connected account requires it. Without it the account counts as not connected, so the wizard
 * simply asks to connect again (which proves the account at panomc.com and issues a new cookie).
 * The token lives in memory only: a restart during setup means connecting once more.
 */
@Lazy
@Component
@Scope(value = ConfigurableBeanFactory.SCOPE_SINGLETON)
class SetupAccountSession {
    @Volatile
    private var tokenHash: ByteArray? = null

    private val random = SecureRandom()

    /** Issues a fresh token for this browser; any earlier browser loses access. */
    fun issue(context: RoutingContext) {
        val bytes = ByteArray(32).also { random.nextBytes(it) }
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

        tokenHash = hash(token)

        val cookie = Cookie.cookie(COOKIE_NAME, token)
        cookie.path = "/"
        cookie.isHttpOnly = true
        cookie.isSecure = isSecure(context)
        cookie.sameSite = CookieSameSite.STRICT

        context.response().addCookie(cookie)
    }

    fun clear() {
        tokenHash = null
    }

    fun matches(context: RoutingContext): Boolean = matches(context.request().getCookie(COOKIE_NAME)?.value)

    internal fun matches(token: String?): Boolean {
        val expected = tokenHash ?: return false

        if (token.isNullOrEmpty()) return false

        return MessageDigest.isEqual(expected, hash(token))
    }

    /** Throws `CONNECT_REQUIRED` (reason `SETUP_SESSION`) when this browser did not connect the account. */
    fun require(context: RoutingContext) {
        if (!matches(context)) {
            throw PanoHostError.of(
                PanoHostException(PanoHostException.CONNECT_REQUIRED, extras = JsonObject().put("reason", "SETUP_SESSION"))
            )
        }
    }

    internal fun issueForTest(token: String) {
        tokenHash = hash(token)
    }

    private fun hash(token: String) = MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8))

    private fun isSecure(context: RoutingContext): Boolean {
        val request = context.request()

        if (request.isSSL) return true

        return request.getHeader("X-Forwarded-Proto")
            ?.split(',')
            ?.any { it.trim().equals("https", ignoreCase = true) } == true
    }

    companion object {
        const val COOKIE_NAME = "pano_setup_session"
    }
}
