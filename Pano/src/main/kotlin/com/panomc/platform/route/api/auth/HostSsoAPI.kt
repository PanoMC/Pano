package com.panomc.platform.route.api.auth

import com.panomc.platform.Main
import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.config.ConfigManager
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.hosted.HostSsoGuard
import com.panomc.platform.hosted.HostSsoUserMapper
import com.panomc.platform.hosted.PanoHostClient
import com.panomc.platform.hosted.PanoHostManager
import com.panomc.platform.model.*
import com.panomc.platform.setup.SetupManager
import com.panomc.platform.util.CSRFTokenGenerator
import com.panomc.platform.util.TrustedProxyIpResolver
import io.vertx.core.Handler
import io.vertx.core.http.HttpMethod
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import org.slf4j.Logger

/**
 * `GET /panel/host-sso?t=<ticket>`: "Open panel" / support access from panomc.com (host-api.md §SSO).
 * Order 1 like every endpoint, so it answers ahead of the panel proxy. The ticket is redeemed
 * server-to-server; the identity becomes a local panel session (auth cookies) and the browser
 * goes to `/panel`. Any failure goes to `/panel/login` without detail — the ticket is single-use
 * and burnt by the control plane either way.
 *
 * [RateLimitManager][com.panomc.platform.util.RateLimitManager] only covers `/api/`, so this route
 * throttles itself ([HostSsoGuard]) before any control-plane call, keyed on the client IP resolved
 * through `server.trusted-proxies` (Traefik's private range on Pano Host), and throttles its logs.
 */
@Endpoint
class HostSsoAPI(
    private val panoHostManager: PanoHostManager,
    private val authProvider: AuthProvider,
    private val databaseManager: DatabaseManager,
    private val setupManager: SetupManager,
    private val configManager: ConfigManager,
    private val logger: Logger
) : Api() {
    companion object {
        private val TICKET = Regex("^[A-Za-z0-9._~-]{16,256}$")
    }

    internal var guard = HostSsoGuard()

    private class Throttled : Exception()

    override val paths = listOf(Path("/panel/host-sso", RouteType.GET))

    override val maintenanceAccess = MaintenanceAccess.ALWAYS

    override fun isAllowedInDemo(method: HttpMethod) = false

    override fun bodyHandler(): Handler<RoutingContext>? = null

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository).build()

    /** No setup/demo/maintenance exceptions: every outcome is a redirect. */
    override suspend fun onBeforeHandle(context: RoutingContext) = Unit

    override suspend fun handle(context: RoutingContext): Result? {
        val target = try {
            signIn(context)
            "/panel"
        } catch (_: Throttled) {
            warn("Pano Host SSO throttled")
            "/panel/login"
        } catch (e: PanoHostClient.HostApiException) {
            warn("Pano Host SSO rejected: ${e.message}")
            "/panel/login"
        } catch (e: HostSsoUserMapper.Denied) {
            warn("Pano Host SSO denied: ${e.message}")
            "/panel/login"
        } catch (e: Exception) {
            warn("Pano Host SSO failed: ${e.javaClass.simpleName}")
            "/panel/login"
        }

        context.response()
            .setStatusCode(302)
            .putHeader("Location", target)
            .putHeader("Cache-Control", "no-store")
            .putHeader("Referrer-Policy", "no-referrer")
            .end()

        return null
    }

    private suspend fun signIn(context: RoutingContext) {
        if (!panoHostManager.ssoEnabled) throw HostSsoUserMapper.Denied("not a Pano Host instance")
        if (!setupManager.isSetupDone()) throw HostSsoUserMapper.Denied("setup not finished")
        if (Main.IS_DEMO) throw HostSsoUserMapper.Denied("demo")

        val ticket = context.queryParam("t").firstOrNull()?.takeIf { TICKET.matches(it) }
            ?: throw HostSsoUserMapper.Denied("missing or malformed ticket")

        val clientIp = clientIp(context)

        if (!guard.tryAcquire(clientIp)) throw Throttled()

        val userId = try {
            panoHostManager.redeem(ticket)
        } finally {
            guard.release()
        }

        val sqlClient = getSqlClient()
        val username = databaseManager.userDao.getUsernameFromUserId(userId, sqlClient)
            ?: throw HostSsoUserMapper.Denied("user vanished")

        val token = authProvider.login(username, context, sqlClient)

        databaseManager.userDao.updateLastLoginDate(userId, sqlClient)

        authProvider.setCookies(context, token, CSRFTokenGenerator.nextToken())

        databaseManager.userDao.getById(userId, sqlClient)?.let { authProvider.runOnAfterLogin(it, context, sqlClient) }
    }

    private fun clientIp(context: RoutingContext): String {
        val proxies = try {
            configManager.config.server.trustedProxies
        } catch (_: Throwable) {
            emptyList()
        }

        return TrustedProxyIpResolver.resolve(context.request(), proxies)?.ip ?: "unknown"
    }

    private fun warn(message: String) = guard.log { suppressed ->
        if (suppressed > 0) logger.warn("{} ({} similar messages suppressed)", message, suppressed)
        else logger.warn(message)
    }
}
