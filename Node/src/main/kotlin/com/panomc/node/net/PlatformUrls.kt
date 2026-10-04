package com.panomc.node.net

import com.panomc.node.config.NodeConfig

/**
 * Turns a URL Pano sent into one this node can actually fetch.
 *
 * Pano cannot write absolute URLs for the artifacts it serves itself. It does not know which
 * address this particular node reaches it on — a LAN address behind NAT, an SSH tunnel on
 * `127.0.0.1:18088`, the public hostname, all for the same Pano — and the one address that is
 * certainly right is the one the node is already connected on. So anything Pano hosts is sent as a
 * path (`/api/node/plugin-jars/spigot`) and joined to that address here.
 *
 * Absolute URLs pass through untouched, which is every third-party download: a Paper build, a
 * Modrinth file, a GitHub release asset. Protocol-relative `//host/path` is deliberately left alone
 * as well rather than being mistaken for a path of ours — [com.panomc.node.util.Downloader] refuses
 * it for having no scheme, which is the correct outcome for a URL nobody meant to send.
 */
class PlatformUrls(private val config: NodeConfig) {
    /** [url] made absolute against this node's configured Pano, or null when there was no url. */
    fun resolve(url: String?): String? = resolve(url, config.platformUrl)

    /** The address this node reaches Pano on, or null while unpaired or misconfigured. */
    fun endpoint(): PlatformEndpoint? = runCatching { PlatformEndpoint.parse(config.platformUrl) }.getOrNull()

    companion object {
        fun resolve(url: String?, platformUrl: String): String? {
            val trimmed = url?.trim()?.takeIf { it.isNotEmpty() } ?: return null

            if (!trimmed.startsWith("/") || trimmed.startsWith("//")) {
                return trimmed
            }

            val base = baseUrl(platformUrl) ?: return trimmed

            return base + trimmed
        }

        /**
         * The Pano address without its trailing slash.
         *
         * A value that already has a scheme is used exactly as it stands, ports and all: this is
         * the address the socket is open on, and rewriting it is how a tunnel on `:18088` becomes
         * a download from a port nothing is listening on. Only a scheme-less value — a
         * hand-written `config.conf` saying `10.0.0.5:8080` — is put through [PlatformEndpoint],
         * because no HTTP client will take it otherwise.
         */
        private fun baseUrl(platformUrl: String): String? {
            val trimmed = platformUrl.trim().trimEnd('/').takeIf { it.isNotEmpty() } ?: return null

            if (trimmed.startsWith("http://", ignoreCase = true) ||
                trimmed.startsWith("https://", ignoreCase = true)
            ) {
                return trimmed
            }

            return try {
                PlatformEndpoint.parse(trimmed).baseUrl
            } catch (_: Exception) {
                trimmed
            }
        }
    }
}
