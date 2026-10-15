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
 * `X-Forwarded-Host` is guaranteed too: SvelteKit reads its origin from it, and without it a custom
 * app answers every form POST with 403. A value from a trusted peer stays; otherwise it is the
 * request's own `Host`.
 *
 * `X-Forwarded-For` is always (re)written with the client address resolved by
 * [TrustedProxyIpResolver.resolveClientIp]: on a direct install the UI's only peer is this proxy, so
 * without it every visitor the theme reports (`/api/v1/visitor-visit`) would be 127.0.0.1, and a header
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

        // SvelteKit takes its origin from this header (HOST_HEADER=x-forwarded-host). A trusted
        // reverse proxy's value stays; anything else is replaced by the authority the visitor used.
        val peer = request.proxiedRequest().remoteAddress()?.host()?.let { TrustedProxyIpResolver.normalizeIp(it) }
        val peerTrusted = peer != null && TrustedProxyIpResolver.isTrustedPeer(peer, trustedProxies())
        val forwardedHost = forwardedHostFor(
            peerTrusted,
            headers.get(X_FORWARDED_HOST),
            request.proxiedRequest().authority()?.toString()
        )

        if (forwardedHost != null) {
            headers.set(X_FORWARDED_HOST, forwardedHost)
        }

        // Always one value, resolved by the shared rule: a client-supplied X-Forwarded-For on a
        // direct install must not reach the UI (which forwards it to the API as the visitor's
        // address), and a chain from a trusted proxy collapses to the real client hop.
        val clientIp = TrustedProxyIpResolver.resolveClientIp(request.proxiedRequest(), trustedProxies())

        if (clientIp != "unknown") {
            headers.set(X_FORWARDED_FOR, clientIp)
        }

        CLIENT_ADDRESS_HEADERS.forEach { name ->
            if (headers.contains(name)) {
                if (clientIp != "unknown") headers.set(name, clientIp) else headers.remove(name)
            }
        }

        headers.remove(FORWARDED)

        return context.sendRequest()
    }

    companion object {
        const val X_FORWARDED_PROTO = "X-Forwarded-Proto"
        const val X_FORWARDED_FOR = "X-Forwarded-For"
        const val X_FORWARDED_HOST = "X-Forwarded-Host"

        /**
         * The `X-Forwarded-Host` to send upstream: the incoming value when a trusted peer sent one,
         * otherwise the authority of the inbound request. Null when neither exists (nothing is set).
         */
        fun forwardedHostFor(trustedPeer: Boolean, incoming: String?, authority: String?): String? {
            if (trustedPeer && !incoming.isNullOrBlank()) {
                return incoming
            }

            return authority?.takeIf { it.isNotBlank() }
        }

        const val FORWARDED = "Forwarded"

        private val CLIENT_ADDRESS_HEADERS = listOf("CF-Connecting-IP", "True-Client-IP", "X-Real-IP")
    }
}
