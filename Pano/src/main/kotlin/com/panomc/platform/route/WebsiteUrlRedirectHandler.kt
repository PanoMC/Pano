package com.panomc.platform.route

import com.panomc.platform.config.ConfigManager
import com.panomc.platform.setup.SetupManager
import io.vertx.core.Handler
import io.vertx.ext.web.RoutingContext
import org.slf4j.Logger
import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.context.annotation.Lazy
import org.springframework.context.annotation.Scope
import org.springframework.stereotype.Component
import java.net.URI

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
    fun create(): Handler<RoutingContext> = Handler { ctx ->
        if (shouldRedirect(ctx)) {
            redirect(ctx)
        } else {
            ctx.next()
        }
    }

    private fun shouldRedirect(ctx: RoutingContext): Boolean {
        val config = configManager.config

        if (!config.websiteUrlRedirect) return false
        if (config.websiteUrl.isBlank()) return false
        if (!setupManager.isSetupDone()) return false

        val request = ctx.request()
        val path = request.path()

        if (path.startsWith("/api/")) return false
        if (path.startsWith("/.well-known/acme-challenge/")) return false

        // WebSocket upgrades and EventSource streams can't follow 307s usefully.
        val upgrade = request.getHeader("Upgrade")?.lowercase()
        if (upgrade == "websocket") return false

        if (isLocalClient(ctx)) return false

        val configuredHost = extractHost(config.websiteUrl) ?: return false
        val requestHost = currentRequestHost(ctx) ?: return false

        return !configuredHost.equals(requestHost, ignoreCase = true)
    }

    private fun redirect(ctx: RoutingContext) {
        val request = ctx.request()
        val configuredBase = configManager.config.websiteUrl.trimEnd('/')
        val query = request.query()
        val target = buildString {
            append(configuredBase)
            append(request.path())
            if (!query.isNullOrBlank()) {
                append('?')
                append(query)
            }
        }

        logger.debug("Redirecting cross-host request {} -> {}", request.absoluteURI(), target)

        ctx.response()
            .setStatusCode(307)
            .putHeader("Location", target)
            .putHeader("Cache-Control", "no-store")
            .end()
    }

    private fun isLocalClient(ctx: RoutingContext): Boolean {
        val request = ctx.request()
        val forwardedFor = request.getHeader("X-Forwarded-For")
            ?.split(",")
            ?.firstOrNull()
            ?.trim()
        val realIp = request.getHeader("X-Real-IP")?.trim()
        val socketHost = request.remoteAddress()?.host()
        val ip = forwardedFor?.takeIf { it.isNotEmpty() }
            ?: realIp?.takeIf { it.isNotEmpty() }
            ?: socketHost
            ?: return false

        return ip == "127.0.0.1" || ip == "::1" || ip == "localhost" || ip.startsWith("0:0:0:0:0:0:0:1")
    }

    private fun currentRequestHost(ctx: RoutingContext): String? {
        val request = ctx.request()
        val forwardedHost = request.getHeader("X-Forwarded-Host")
            ?.split(",")
            ?.firstOrNull()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        val hostHeader = forwardedHost ?: request.getHeader("Host") ?: return null
        return stripPort(hostHeader.trim()).takeIf { it.isNotEmpty() }
    }

    /**
     * Strips an optional `:port` while keeping IPv6 brackets intact (`[::1]:8080` -> `[::1]`).
     */
    private fun stripPort(host: String): String {
        if (host.startsWith("[")) {
            val end = host.indexOf(']')
            return if (end >= 0) host.substring(0, end + 1) else host
        }
        val colon = host.indexOf(':')
        return if (colon >= 0) host.substring(0, colon) else host
    }

    private fun extractHost(url: String): String? {
        val raw = url.trim()
        if (raw.isEmpty()) return null
        return try {
            val normalized = if (raw.contains("://")) raw else "https://$raw"
            URI(normalized).host?.takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
    }
}
