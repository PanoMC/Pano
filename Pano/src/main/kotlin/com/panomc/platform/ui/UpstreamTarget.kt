package com.panomc.platform.ui

import com.panomc.platform.config.PanoConfig
import io.vertx.core.Future
import io.vertx.core.http.HttpClient
import io.vertx.core.http.HttpMethod
import io.vertx.core.http.RequestOptions
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.URI

/**
 * Where an `EXTERNAL` front-end (or the theme dev server, `dev-url`) answers: a scheme, a host and a
 * port, nothing else. [HttpProxy][io.vertx.httpproxy.HttpProxy] forwards to an address, so a path
 * prefix cannot be honoured and is refused instead of being dropped silently.
 */
class UpstreamTarget private constructor(
    val host: String,
    val port: Int,
    val ssl: Boolean
) {
    /** `http://127.0.0.1:4000`, the form that is saved and compared. */
    val origin: String get() = "${if (ssl) "https" else "http"}://${if (':' in host) "[$host]" else host}:$port"

    /** Whether this address can only be this machine's own Pano: the Pano ports on a local address. */
    fun pointsAtPano(config: PanoConfig, resolveLocal: Boolean = true): Boolean {
        val server = config.server

        if (port != server.httpPort && port != server.httpsPort) {
            return false
        }

        if (host.equals("localhost", true) || host == "0.0.0.0" || host == "::" || host.equals(server.host, true)) {
            return true
        }

        runCatching { URI(config.websiteUrl.trim()).host }.getOrNull()?.let {
            if (it.equals(host, true)) return true
        }

        if (!resolveLocal) {
            return false
        }

        return runCatching {
            val address = InetAddress.getByName(host)

            address.isLoopbackAddress || address.isAnyLocalAddress || NetworkInterface.getByInetAddress(address) != null
        }.getOrDefault(false)
    }

    /**
     * Whether anything answers HTTP there within [timeoutMs]. Any status counts: a 404 from a
     * front-end that has no `/` route yet still proves a server is listening.
     */
    fun probe(httpClient: HttpClient, timeoutMs: Long = PROBE_TIMEOUT_MS): Future<Boolean> =
        httpClient.request(
            RequestOptions()
                .setMethod(HttpMethod.GET)
                .setHost(host)
                .setPort(port)
                .setSsl(ssl)
                .setURI("/")
                .setTimeout(timeoutMs)
        ).compose { request -> request.send() }
            .map { true }
            .recover { Future.succeededFuture(false) }

    override fun equals(other: Any?) = other is UpstreamTarget && other.origin == origin

    override fun hashCode() = origin.hashCode()

    override fun toString() = origin

    companion object {
        const val PROBE_TIMEOUT_MS = 5_000L

        /** Parses [raw]; null when it is not an `http(s)://host[:port]` address with an empty path. */
        fun parse(raw: String?): UpstreamTarget? {
            val text = raw?.trim().orEmpty()

            if (text.isEmpty()) return null

            val uri = try {
                URI(text)
            } catch (_: Exception) {
                return null
            }

            val scheme = uri.scheme?.lowercase() ?: return null

            if (scheme != "http" && scheme != "https") return null

            val host = uri.host?.trim('[', ']')?.takeIf { it.isNotEmpty() } ?: return null

            if (uri.userInfo != null || uri.query != null || uri.fragment != null) return null

            if (!uri.rawPath.isNullOrEmpty() && uri.rawPath != "/") return null

            val ssl = scheme == "https"
            val port = if (uri.port == -1) (if (ssl) 443 else 80) else uri.port

            if (port !in 1..65535) return null

            return UpstreamTarget(host, port, ssl)
        }
    }
}
