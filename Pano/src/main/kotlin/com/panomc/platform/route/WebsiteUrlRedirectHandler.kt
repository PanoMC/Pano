package com.panomc.platform.route

import com.panomc.platform.config.ConfigManager
import com.panomc.platform.setup.SetupManager
import com.panomc.platform.util.WebsiteUrlUtil
import io.vertx.core.Handler
import io.vertx.core.http.HttpServerRequest
import io.vertx.ext.web.RoutingContext
import org.slf4j.Logger
import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.context.annotation.Lazy
import org.springframework.context.annotation.Scope
import org.springframework.stereotype.Component

internal enum class RedirectDecision {
    REDIRECT,
    PASS,

    /**
     * The request came through a loopback reverse proxy that does not forward the original
     * Host — the Host header is the proxy's upstream target (e.g. `127.0.0.1:8090`), not what
     * the visitor typed, so a cross-host redirect would bounce legitimate visitors.
     */
    SKIP_UNTRUSTED_PROXY
}

// Cookies are scoped to the website-url host (AuthProvider), so a browser arriving on a different
// host has its auth cookie rejected. Bouncing it to the canonical host fixes that.
@Lazy
@Component
@Scope(value = ConfigurableBeanFactory.SCOPE_SINGLETON)
class WebsiteUrlRedirectHandler(
    private val configManager: ConfigManager,
    private val setupManager: SetupManager,
    private val logger: Logger
) {
    @Volatile
    private var lastUntrustedProxyWarnAt = 0L

    fun create(): Handler<RoutingContext> = Handler { ctx ->
        when (decide(ctx)) {
            RedirectDecision.REDIRECT -> redirect(ctx)
            RedirectDecision.SKIP_UNTRUSTED_PROXY -> {
                warnUntrustedProxy()
                ctx.next()
            }

            RedirectDecision.PASS -> ctx.next()
        }
    }

    private fun decide(ctx: RoutingContext): RedirectDecision {
        val config = configManager.config
        val request = ctx.request()

        return decideRedirect(
            redirectEnabled = config.websiteUrlRedirect,
            websiteUrl = config.websiteUrl,
            setupDone = setupManager.isSetupDone(),
            path = request.path(),
            upgradeHeader = request.getHeader("Upgrade"),
            clientIp = resolveClientIp(request),
            socketPeerIp = request.remoteAddress()?.host(),
            forwardedHost = firstHeaderValue(request, "X-Forwarded-Host"),
            hostHeader = request.getHeader("Host")
        )
    }

    private fun redirect(ctx: RoutingContext) {
        val request = ctx.request()
        val target = buildRedirectTarget(configManager.config.websiteUrl, request.path(), request.query())

        logger.debug("Redirecting cross-host request {} -> {}", request.absoluteURI(), target)

        ctx.response()
            .setStatusCode(307)
            .putHeader("Location", target)
            .putHeader("Cache-Control", "no-store")
            .end()
    }

    private fun warnUntrustedProxy() {
        val now = System.currentTimeMillis()
        if (now - lastUntrustedProxyWarnAt < UNTRUSTED_PROXY_WARN_INTERVAL_MS) return
        lastUntrustedProxyWarnAt = now
        logger.warn(
            "Skipped cross-host redirect: request arrived via a loopback reverse proxy without " +
                    "X-Forwarded-Host, so the Host header can't be trusted. Add " +
                    "'proxy_set_header X-Forwarded-Host \$host;' (and X-Forwarded-For) to your " +
                    "reverse proxy config."
        )
    }

    private fun resolveClientIp(request: HttpServerRequest): String? {
        val forwardedFor = firstHeaderValue(request, "X-Forwarded-For")
        val realIp = request.getHeader("X-Real-IP")?.trim()

        return forwardedFor?.takeIf { it.isNotEmpty() }
            ?: realIp?.takeIf { it.isNotEmpty() }
            ?: request.remoteAddress()?.host()
    }

    private fun firstHeaderValue(request: HttpServerRequest, header: String) = request.getHeader(header)
        ?.split(",")
        ?.firstOrNull()
        ?.trim()

    companion object {
        private const val UNTRUSTED_PROXY_WARN_INTERVAL_MS = 60_000L

        internal fun decideRedirect(
            redirectEnabled: Boolean,
            websiteUrl: String,
            setupDone: Boolean,
            path: String,
            upgradeHeader: String?,
            clientIp: String?,
            socketPeerIp: String?,
            forwardedHost: String?,
            hostHeader: String?
        ): RedirectDecision {
            if (!redirectEnabled) return RedirectDecision.PASS
            if (websiteUrl.isBlank()) return RedirectDecision.PASS
            if (!setupDone) return RedirectDecision.PASS

            if (path.startsWith("/api/")) return RedirectDecision.PASS
            if (path.startsWith("/.well-known/acme-challenge/")) return RedirectDecision.PASS

            // WebSocket upgrades and EventSource streams can't follow 307s usefully.
            if (upgradeHeader?.lowercase() == "websocket") return RedirectDecision.PASS

            if (clientIp != null && isLoopbackIp(clientIp)) return RedirectDecision.PASS

            if (socketPeerIp != null && isLoopbackIp(socketPeerIp) && forwardedHost.isNullOrBlank()) {
                return RedirectDecision.SKIP_UNTRUSTED_PROXY
            }

            val configuredHost = WebsiteUrlUtil.host(websiteUrl) ?: return RedirectDecision.PASS
            val requestHostRaw = forwardedHost?.takeIf { it.isNotBlank() }
                ?: hostHeader
                ?: return RedirectDecision.PASS
            val requestHost = WebsiteUrlUtil.stripPort(requestHostRaw.trim()).takeIf { it.isNotEmpty() }
                ?: return RedirectDecision.PASS

            return if (configuredHost.equals(requestHost, ignoreCase = true)) {
                RedirectDecision.PASS
            } else {
                RedirectDecision.REDIRECT
            }
        }

        internal fun buildRedirectTarget(websiteUrl: String, path: String, query: String?): String {
            val configuredBase = websiteUrl.trimEnd('/')

            return buildString {
                append(configuredBase)
                append(path)
                if (!query.isNullOrBlank()) {
                    append('?')
                    append(query)
                }
            }
        }

        internal fun isLoopbackIp(ip: String): Boolean =
            ip == "127.0.0.1" || ip == "::1" || ip == "localhost" || ip.startsWith("0:0:0:0:0:0:0:1")
    }
}
