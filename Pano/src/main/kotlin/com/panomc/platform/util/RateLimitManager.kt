package com.panomc.platform.util

import com.panomc.platform.access.AccessContext
import com.panomc.platform.config.ConfigManager
import com.panomc.platform.error.RateLimitExceeded
import com.panomc.platform.maintenance.MaintenanceModeManager.Companion.ERROR_PARAM
import com.panomc.platform.maintenance.MaintenanceModeManager.Companion.ERROR_RATE_LIMITED
import com.panomc.platform.route.ApiPaths
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
 * - FRONTEND_KEY:     Generous - one front-end key that declares no client IP (doc 05 §7)
 *
 * Traffic of a front-end key never shares a bucket with direct visitors: a stored key with a declared
 * client IP is counted as `k<keyId>:<ip>` in a second limiter set, a stored key without one as
 * `k<keyId>` in [Tier.FRONTEND_KEY], and the internal key of a loopback peer (Pano's own UIs) is not
 * limited, exactly as server-side rendering was before keys existed.
 *
 * The maintenance tiers exist because `/api/v1/maintenance/login` is the only online way back into a
 * closed site. Every other tier buckets on [getClientIp], which trusts `X-Forwarded-For`; sharing a
 * bucket with those paths would let anyone drain an admin's budget by flooding a *different*
 * endpoint with the admin's IP in a header. The maintenance arms therefore key on the socket peer
 * ([TrustedProxyIpResolver]) inside limiters no spoofable-key path can reach.
 */
@Component
class RateLimitManager(
    private val configManager: ConfigManager,
    private val proxyDetector: ProxyDetector
) {

    companion object {
        private const val HEADER_LIMIT = "X-RateLimit-Limit"
        private const val HEADER_REMAINING = "X-RateLimit-Remaining"
        private const val HEADER_RETRY_AFTER = "Retry-After"

        /** The tier of an already normalised request path; everything outside the API falls to [Tier.PUBLIC_API]. */
        internal fun tierForPath(path: String): Tier {
            return when {
                // Read on every navigation of a server-side front-end; they are not brute-force targets.
                isPath(path, "/auth/credentials") -> Tier.PUBLIC_API
                isPath(path, "/auth/csrf") -> Tier.PUBLIC_API
                isPath(path, "/auth/ws-ticket") -> Tier.PUBLIC_API
                path.startsWith(ApiPaths.core("/auth/")) -> Tier.AUTH
                path.startsWith(ApiPaths.core("/maintenance/login")) -> Tier.MAINTENANCE_AUTH
                path.startsWith(ApiPaths.core("/maintenance/")) -> Tier.MAINTENANCE_API
                path.startsWith(ApiPaths.core("/server/connect")) -> Tier.SERVER_API
                path.startsWith(ApiPaths.core("/server/disconnect")) -> Tier.SERVER_API
                path.startsWith(ApiPaths.core("/server/connection")) -> Tier.SERVER_API
                path.startsWith(ApiPaths.core("/setup/")) -> Tier.SETUP_API
                path.startsWith(ApiPaths.core("/favicon")) -> Tier.FILE_SERVE
                path.startsWith(ApiPaths.core("/website-logo")) -> Tier.FILE_SERVE
                path.startsWith(ApiPaths.core("/server/icon/")) -> Tier.FILE_SERVE
                path.startsWith(ApiPaths.core("/posts/thumbnails/")) -> Tier.FILE_SERVE
                path.startsWith(ApiPaths.core("/profile/picture/")) -> Tier.FILE_SERVE
                ApiPaths.isPanelApi(path) -> Tier.PANEL_API
                else -> Tier.PUBLIC_API
            }
        }

        private fun isPath(path: String, core: String): Boolean {
            val full = ApiPaths.core(core)

            return path == full || path == "$full/"
        }

        /** Whether the socket peer itself is loopback (the forwarding headers are not looked at). */
        internal fun isLoopbackPeer(socketPeer: String?): Boolean {
            val peer = TrustedProxyIpResolver.normalizeIp(socketPeer) ?: return false

            return WebsiteUrlRedirectHandler.isLoopbackIp(peer.removePrefix("::ffff:"))
        }

        /**
         * The "someone on the box" exemption: the socket peer itself is loopback and nothing claims to be
         * forwarding. A forwarded address, even `127.0.0.1`, never buys it, because the exemption is read
         * from the socket and not from the resolved client address.
         */
        internal fun isDirectLoopback(socketPeer: String?, hasForwardingHeader: Boolean): Boolean {
            if (hasForwardingHeader) return false

            val peer = TrustedProxyIpResolver.normalizeIp(socketPeer) ?: return false

            return WebsiteUrlRedirectHandler.isLoopbackIp(peer.removePrefix("::ffff:"))
        }
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
        ),
        FRONTEND_KEY(
            maxRequests = 2000,
            refillMs = 10,
            description = "A front-end key that declares no client IP"
        )
    }

    private val limiters = newLimiters()

    /** The same tiers for traffic of a stored front-end key: `k<keyId>:<ip>` and `k<keyId>` buckets live only here. */
    private val keyedLimiters = newLimiters()

    private fun newLimiters() = Tier.entries.associateWith { tier ->
        RateLimiter(
            maxTokens = tier.maxRequests,
            refillRateMs = tier.refillMs
        )
    }

    private fun limiter(tier: Tier, keyed: Boolean) = (if (keyed) keyedLimiters else limiters)[tier]!!

    fun getTierForPath(path: String): Tier = tierForPath(path)

    /**
     * The client address the access plane decided on ([AccessContext]); without it (the handler did not
     * run) the shared resolution ([TrustedProxyIpResolver.resolveClientIp]): a forwarded address only
     * from a trusted peer.
     */
    fun getClientIp(context: RoutingContext): String =
        AccessContext.of(context)?.clientIp
            ?: TrustedProxyIpResolver.resolveClientIp(context.request(), trustedProxies())

    fun isAllowed(clientIp: String, tier: Tier, keyed: Boolean = false): Boolean =
        limiter(tier, keyed).tryAcquire(clientIp)

    fun remaining(clientIp: String, tier: Tier, keyed: Boolean = false): Int =
        limiter(tier, keyed).remainingTokens(clientIp)

    fun retryAfter(clientIp: String, tier: Tier, keyed: Boolean = false): Int =
        limiter(tier, keyed).retryAfterSeconds(clientIp)

    fun createHandler(): Handler<RoutingContext> {
        return Handler { context ->
            // Route matching uses the normalized path (RouteState defaults useNormalizedPath=true),
            // so tiering must use it too: "/%61pi/maintenance/login" matches the endpoint at order 1
            // but would dodge every arm below if the raw request line were used here.
            val path = context.normalizedPath() ?: ""

            if (!ApiPaths.isMounted(path)) {
                context.next()
                return@Handler
            }

            if (context.request().method().name() == "OPTIONS") {
                context.next()
                return@Handler
            }

            // Taken before getClientIp so no header can move a maintenance request into the
            // loopback skip, and before the shared buckets so no other path can drain it.
            if (path.startsWith(ApiPaths.core("/maintenance/"))) {
                handleMaintenance(context, getTierForPath(path))
                return@Handler
            }

            val clientIp = getClientIp(context)

            val request = context.request()
            val socketPeer = request.remoteAddress()?.host()
            val key = AccessContext.of(context)?.key

            if (key == null) {
                proxyDetector.observe(
                    socketPeer,
                    clientIp,
                    request.getHeader("X-Forwarded-For"),
                    TrustedProxyIpResolver.hasForwardingHeader(request),
                    TrustedProxyIpResolver.normalizeIp(socketPeer)?.let { TrustedProxyIpResolver.isTrustedPeer(it, trustedProxies()) } ?: false
                )

                // Skip rate limiting only for a direct loopback connection (the operator on the box, a local
                // MC plugin); never for a client address that merely claims to be localhost.
                if (isDirectLoopback(socketPeer, TrustedProxyIpResolver.hasForwardingHeader(request))) {
                    context.next()
                    return@Handler
                }

                enforce(context, getTierForPath(path), clientIp, keyed = false)
                return@Handler
            }

            // Pano's own UIs (server-side rendering) carry the internal key from this machine: not limited,
            // as before keys existed. Forwarding headers do not matter here, the key is the proof.
            if (key.isInternal && isLoopbackPeer(socketPeer)) {
                context.next()
                return@Handler
            }

            // A front-end key has buckets of its own, so it can neither drain nor borrow a visitor's.
            if (AccessContext.of(context)?.clientIpFromKey == true) {
                enforce(context, getTierForPath(path), "k${key.id}:$clientIp", keyed = true)
            } else {
                enforce(context, Tier.FRONTEND_KEY, "k${key.id}", keyed = true)
            }
        }
    }

    private fun enforce(context: RoutingContext, tier: Tier, bucket: String, keyed: Boolean) {
        if (isAllowed(bucket, tier, keyed)) {
            context.response()
                .putHeader(HEADER_LIMIT, tier.maxRequests.toString())
                .putHeader(HEADER_REMAINING, remaining(bucket, tier, keyed).toString())

            context.next()
        } else {
            val retryAfterVal = retryAfter(bucket, tier, keyed)

            val error = RateLimitExceeded(extras = mapOf("retryAfter" to retryAfterVal))

            context.response()
                .putHeader(HEADER_LIMIT, tier.maxRequests.toString())
                .putHeader(HEADER_REMAINING, "0")
                .putHeader(HEADER_RETRY_AFTER, retryAfterVal.toString())
                .putHeader("content-type", "application/json; charset=utf-8")
                .setStatusCode(error.getStatusCode())
                .setStatusMessage(error.getStatusMessage())
                .end(error.encode())
        }
    }

    /**
     * Everything under `/api/v1/maintenance/` never touches [getClientIp]: the key is the socket peer, honouring
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
