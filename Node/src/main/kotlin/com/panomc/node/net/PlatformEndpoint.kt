package com.panomc.node.net

import java.net.URI

/**
 * Where the Pano this node belongs to actually is.
 *
 * A scheme is required unless the host is obviously local. That is not pedantry: without one the
 * only safe default is HTTPS, and silently falling back to plain HTTP would send the pairing code
 * and the bearer token in the clear to whoever managed to block the HTTPS attempt. An operator who
 * really is running Pano over HTTP writes `http://`, and Pano's own local node always does.
 */
data class PlatformEndpoint(
    val host: String,
    val port: Int,
    val ssl: Boolean
) {
    val baseUrl: String get() = "${if (ssl) "https" else "http"}://$host:$port"

    companion object {
        fun parse(value: String): PlatformEndpoint {
            val trimmed = value.trim().trimEnd('/')

            require(trimmed.isNotEmpty()) { "Platform URL is empty." }

            if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
                val uri = URI(trimmed)
                val ssl = uri.scheme == "https"
                val host = uri.host ?: throw IllegalArgumentException("Platform URL has no host.")
                val port = if (uri.port != -1) uri.port else if (ssl) 443 else 80

                require(port in 1..65535) { "Platform port $port is out of range." }

                return PlatformEndpoint(host, port, ssl)
            }

            if (trimmed.contains(':')) {
                val parts = trimmed.split(':')
                val host = parts[0]
                val port = parts.getOrNull(1)?.toIntOrNull()
                    ?: throw IllegalArgumentException("Platform port \"${parts.getOrNull(1)}\" is not a number.")

                require(port in 1..65535) { "Platform port $port is out of range." }

                return PlatformEndpoint(host, port, !isLocal(host))
            }

            return if (isLocal(trimmed)) {
                PlatformEndpoint(trimmed, 80, false)
            } else {
                PlatformEndpoint(trimmed, 443, true)
            }
        }

        /** Hosts where plain HTTP never leaves the machine or the private network. */
        internal fun isLocal(host: String): Boolean {
            val lower = host.lowercase()

            if (lower == "localhost" || lower == "127.0.0.1" || lower == "::1" || lower == "[::1]") {
                return true
            }

            if (lower.startsWith("10.") || lower.startsWith("192.168.")) {
                return true
            }

            val octets = lower.split('.')

            if (octets.size == 4 && octets[0] == "172") {
                val second = octets[1].toIntOrNull() ?: return false

                return second in 16..31
            }

            return false
        }
    }
}
