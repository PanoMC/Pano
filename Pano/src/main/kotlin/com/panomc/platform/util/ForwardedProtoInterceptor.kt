package com.panomc.platform.util

import io.vertx.core.Future
import io.vertx.httpproxy.ProxyContext
import io.vertx.httpproxy.ProxyInterceptor
import io.vertx.httpproxy.ProxyResponse

/**
 * Guarantees the UI upstream sees an `X-Forwarded-Proto` header.
 *
 * The SvelteKit apps are started with `PROTOCOL_HEADER=x-forwarded-proto` so `event.url` carries
 * the scheme visitors actually use. A reverse proxy in front of Pano (nginx, Traefik, Cloudflare)
 * already sets the header and is left alone; a direct install has none, and adapter-node would
 * then assume `https` even on plain-http sites. Fill it from the inbound connection instead.
 *
 * `X-Forwarded-For` is always (re)written with the client address resolved by
 * [TrustedProxyIpResolver.resolveClientIp]: on a direct install the UI's only peer is this proxy, so
 * without it every visitor the theme reports (`/api/visitorVisit`) would be 127.0.0.1, and a header
 * a visitor made up must not be passed on as if a proxy had set it.
 */
class ForwardedProtoInterceptor(
    private val trustedProxies: () -> List<String> = { emptyList() }
) : ProxyInterceptor {
    override fun handleProxyRequest(context: ProxyContext): Future<ProxyResponse> {
        val request = context.request()
        val headers = request.headers()

        if (!headers.contains(X_FORWARDED_PROTO)) {
            headers.set(X_FORWARDED_PROTO, if (request.proxiedRequest().isSSL) "https" else "http")
        }

        // Always one value, resolved by the shared rule: a client-supplied X-Forwarded-For on a
        // direct install must not reach the UI (which forwards it to the API as the visitor's
        // address), and a chain from a trusted proxy collapses to the real client hop.
        val clientIp = TrustedProxyIpResolver.resolveClientIp(request.proxiedRequest(), trustedProxies())

        if (clientIp != "unknown") {
            headers.set(X_FORWARDED_FOR, clientIp)
        }

        return context.sendRequest()
    }

    companion object {
        const val X_FORWARDED_PROTO = "X-Forwarded-Proto"
        const val X_FORWARDED_FOR = "X-Forwarded-For"
    }
}
