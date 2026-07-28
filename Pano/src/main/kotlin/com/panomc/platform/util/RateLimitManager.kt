package com.panomc.platform.util

import com.panomc.platform.config.ConfigManager
import com.panomc.platform.maintenance.MaintenanceModeManager.Companion.ERROR_PARAM
import com.panomc.platform.maintenance.MaintenanceModeManager.Companion.ERROR_RATE_LIMITED
import com.panomc.platform.route.WebsiteUrlRedirectHandler
import io.vertx.core.Handler
import io.vertx.ext.web.RoutingContext
import org.springframework.stereotype.Component

/**
 * Centralized rate limit management for all API endpoints.
 *
 * Rate limit tiers:
 * - AUTH:             Very strict - protects login/register/password reset against brute force
 * - MAINTENANCE_AUTH: Same budget as AUTH, but a bucket namespace of its own (see below)
 * - MAINTENANCE_API:  Moderate - the maintenance skip/exit endpoints
 * - PUBLIC_API:       Moderate - for unauthenticated public API endpoints
 * - PANEL_API:        Generous - for authenticated panel API endpoints
 * - SERVER_API:       Strict - for MC plugin server connect/disconnect endpoints
 * - FILE_SERVE:       Generous - for static file serving (favicon, logo, thumbnails)
 *
 * The maintenance tiers exist because `/api/maintenance/login` is the only online way back into a
 * closed site. Every other tier buckets on [getClientIp], which trusts `X-Forwarded-For`; sharing a
 * bucket with those paths would let anyone drain an admin's budget by flooding a *different*
 * endpoint with the admin's IP in a header. The maintenance arms therefore key on the socket peer
 * ([TrustedProxyIpResolver]) inside limiters no spoofable-key path can reach.
 */
@Component
class RateLimitManager(
    private val configManager: ConfigManager
) {

    companion object {
        private const val HEADER_LIMIT = "X-RateLimit-Limit"
        private const val HEADER_REMAINING = "X-RateLimit-Remaining"
        private const val HEADER_RETRY_AFTER = "Retry-After"
    }

    enum class Tier(
        val maxRequests: Int,
        val refillMs: Long,
        val description: String
    ) {
        AUTH(
            maxRequests = 10,
            refillMs = 6000,
            description = "Authentication endpoints"
        ),
        MAINTENANCE_AUTH(
            maxRequests = 10,
            refillMs = 6000,
            description = "Maintenance mode login"
        ),
        MAINTENANCE_API(
            maxRequests = 500,
            refillMs = 100,
            description = "Maintenance mode skip/exit endpoints"
        ),
        PUBLIC_API(
            maxRequests = 500,
            refillMs = 100,
            description = "Public API endpoints"
        ),
        PANEL_API(
            maxRequests = 500,
            refillMs = 100,
            description = "Panel API endpoints"
        ),
        SERVER_API(
            maxRequests = 15,
            refillMs = 4000,
            description = "Server connect/disconnect endpoints"
        ),
        FILE_SERVE(
            maxRequests = 500,
            refillMs = 50,
            description = "File serving endpoints"
        ),
        SETUP_API(
            maxRequests = 20,
            refillMs = 3000,
            description = "Setup endpoints"
        )
    }

    private val limiters = Tier.entries.associateWith { tier ->
        RateLimiter(
            maxTokens = tier.maxRequests,
            refillRateMs = tier.refillMs
        )
    }

    fun getTierForPath(path: String): Tier {
        return when {
            path.startsWith("/api/auth/") -> Tier.AUTH
            path.startsWith("/api/maintenance/login") -> Tier.MAINTENANCE_AUTH
            path.startsWith("/api/maintenance/") -> Tier.MAINTENANCE_API
            path.startsWith("/api/server/connect") -> Tier.SERVER_API
            path.startsWith("/api/server/disconnect") -> Tier.SERVER_API
            path.startsWith("/api/server/connection") -> Tier.SERVER_API
            path.startsWith("/api/setup/") -> Tier.SETUP_API
            path.startsWith("/api/favicon") -> Tier.FILE_SERVE
            path.startsWith("/api/website-logo") -> Tier.FILE_SERVE
            path.startsWith("/api/server/icon/") -> Tier.FILE_SERVE
            path.startsWith("/api/post/thumbnail/") -> Tier.FILE_SERVE
            path.startsWith("/api/profile/picture/") -> Tier.FILE_SERVE
            path.startsWith("/api/panel/") -> Tier.PANEL_API
            else -> Tier.PUBLIC_API
        }
    }

    fun getClientIp(context: RoutingContext): String {
        val forwarded = context.request().getHeader("X-Forwarded-For")
        if (forwarded != null) {
            return forwarded.split(",").first().trim()
        }

        val realIp = context.request().getHeader("X-Real-IP")
        if (realIp != null) {
            return realIp.trim()
        }

        return context.request().remoteAddress()?.host() ?: "unknown"
    }

    private fun isLocalhost(ip: String): Boolean {
        return ip == "127.0.0.1" || ip == "::1" || ip == "0:0:0:0:0:0:0:1" || ip == "0.0.0.0" || ip == "localhost"
    }

    fun isAllowed(clientIp: String, tier: Tier): Boolean {
        val limiter = limiters[tier]!!
        return limiter.tryAcquire(clientIp)
    }

    fun remaining(clientIp: String, tier: Tier): Int {
        val limiter = limiters[tier]!!
        return limiter.remainingTokens(clientIp)
    }

    fun retryAfter(clientIp: String, tier: Tier): Int {
        val limiter = limiters[tier]!!
        return limiter.retryAfterSeconds(clientIp)
    }

    fun createHandler(): Handler<RoutingContext> {
        return Handler { context ->
            // Route matching uses the normalized path (RouteState defaults useNormalizedPath=true),
            // so tiering must use it too: "/%61pi/maintenance/login" matches the endpoint at order 1
            // but would dodge every arm below if the raw request line were used here.
            val path = context.normalizedPath() ?: ""

            if (!path.startsWith("/api/")) {
                context.next()
                return@Handler
            }

            if (context.request().method().name() == "OPTIONS") {
                context.next()
                return@Handler
            }

            // Taken before getClientIp so no header can move a maintenance request into the
            // loopback skip, and before the shared buckets so no other path can drain it.
            if (path.startsWith("/api/maintenance/")) {
                handleMaintenance(context, getTierForPath(path))
                return@Handler
            }

            val clientIp = getClientIp(context)

            // Skip rate limiting for localhost requests
            if (isLocalhost(clientIp)) {
                context.next()
                return@Handler
            }

            val tier = getTierForPath(path)

            if (isAllowed(clientIp, tier)) {
                context.response()
                    .putHeader(HEADER_LIMIT, tier.maxRequests.toString())
                    .putHeader(HEADER_REMAINING, remaining(clientIp, tier).toString())

                context.next()
            } else {
                val retryAfterVal = retryAfter(clientIp, tier)

                val responseBody = "{\"result\":\"error\",\"error\":\"RATE_LIMIT_EXCEEDED\",\"retryAfter\":$retryAfterVal}"

                context.response()
                    .putHeader(HEADER_LIMIT, tier.maxRequests.toString())
                    .putHeader(HEADER_REMAINING, "0")
                    .putHeader(HEADER_RETRY_AFTER, retryAfterVal.toString())
                    .putHeader("content-type", "application/json; charset=utf-8")
                    .setStatusCode(429)
                    .setStatusMessage("Too Many Requests")
                    .end(responseBody)
            }
        }
    }

    /**
     * Everything under `/api/maintenance/` never touches [getClientIp]: the key is the socket peer, honouring
     * `server.trusted-proxies` exactly the way the maintenance ban store does, so a spoofed
     * `X-Forwarded-For` can neither drain a third party's budget nor buy an exemption.
     */
    private fun handleMaintenance(context: RoutingContext, tier: Tier) {
        val request = context.request()
        val trustedProxies = trustedProxies()
        val resolved = TrustedProxyIpResolver.resolve(request, trustedProxies)

        // The loopback exemption keeps the documented recovery path open for an operator with shell
        // access, and is evaluated on the socket peer only. It additionally requires that nothing
        // claims to be forwarding: behind an unconfigured same-host reverse proxy every peer is
        // 127.0.0.1, and exempting on that alone would exempt the whole internet in one stroke.
        if (resolved != null &&
            !resolved.fromForwardedHeader &&
            trustedProxies.isEmpty() &&
            !TrustedProxyIpResolver.hasForwardingHeader(request) &&
            WebsiteUrlRedirectHandler.isLoopbackIp(resolved.ip)
        ) {
            context.next()
            return
        }

        val key = resolved?.ip ?: "unknown"

        if (isAllowed(key, tier)) {
            context.response()
                .putHeader(HEADER_LIMIT, tier.maxRequests.toString())
                .putHeader(HEADER_REMAINING, remaining(key, tier).toString())

            context.next()
            return
        }

        // These endpoints are reached by a plain browser form navigation, so a JSON body would
        // dead-end the only way back into a closed site. Bounce back to the maintenance page,
        // which renders the code as a localised notice.
        context.response()
            .putHeader(HEADER_LIMIT, tier.maxRequests.toString())
            .putHeader(HEADER_REMAINING, "0")
            .putHeader(HEADER_RETRY_AFTER, retryAfter(key, tier).toString())
            .putHeader("Location", "/?$ERROR_PARAM=$ERROR_RATE_LIMITED")
            .putHeader("Cache-Control", "no-store")
            .setStatusCode(302)
            .setStatusMessage("Found")
            .end()
    }

    private fun trustedProxies(): List<String> = try {
        configManager.config.server.trustedProxies
    } catch (_: Throwable) {
        // Config is not loaded yet (or the block was hand-deleted): treat every request as direct.
        emptyList()
    }
}
