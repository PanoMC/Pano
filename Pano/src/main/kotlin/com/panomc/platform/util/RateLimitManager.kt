package com.panomc.platform.util

import io.vertx.core.Handler
import io.vertx.ext.web.RoutingContext
import org.springframework.stereotype.Component

/**
 * Centralized rate limit management for all API endpoints.
 *
 * Rate limit tiers:
 * - AUTH:       Very strict - protects login/register/password reset against brute force
 * - PUBLIC_API: Moderate - for unauthenticated public API endpoints
 * - PANEL_API:  Generous - for authenticated panel API endpoints
 * - SERVER_API: Strict - for MC plugin server connect/disconnect endpoints
 * - FILE_SERVE: Generous - for static file serving (favicon, logo, thumbnails)
 */
@Component
class RateLimitManager {

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
        PUBLIC_API(
            maxRequests = 60,
            refillMs = 1000,
            description = "Public API endpoints"
        ),
        PANEL_API(
            maxRequests = 120,
            refillMs = 500,
            description = "Panel API endpoints"
        ),
        SERVER_API(
            maxRequests = 15,
            refillMs = 4000,
            description = "Server connect/disconnect endpoints"
        ),
        FILE_SERVE(
            maxRequests = 200,
            refillMs = 300,
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
            val path = context.request().path() ?: ""

            if (!path.startsWith("/api/")) {
                context.next()
                return@Handler
            }

            if (context.request().method().name() == "OPTIONS") {
                context.next()
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
}
