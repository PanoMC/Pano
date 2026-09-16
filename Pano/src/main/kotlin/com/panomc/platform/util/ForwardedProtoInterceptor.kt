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
 */
class ForwardedProtoInterceptor : ProxyInterceptor {
    override fun handleProxyRequest(context: ProxyContext): Future<ProxyResponse> {
        val request = context.request()
        val headers = request.headers()

        if (!headers.contains(X_FORWARDED_PROTO)) {
            headers.set(X_FORWARDED_PROTO, if (request.proxiedRequest().isSSL) "https" else "http")
        }

        return context.sendRequest()
    }

    companion object {
        const val X_FORWARDED_PROTO = "X-Forwarded-Proto"
    }
}
