package com.panomc.platform.node

import com.panomc.platform.error.BadRequest
import com.panomc.platform.util.WebsiteUrlUtil

/**
 * The address a node being bootstrapped should use to reach Pano, when it is not the website URL.
 *
 * `website-url` is where *browsers* find Pano, and that is usually where a node finds it too — but
 * not always. A node behind NAT on the same LAN reaches Pano at a private address the public
 * hostname does not resolve to; a lab or development Pano is reachable only through an SSH reverse
 * tunnel (`http://127.0.0.1:18088` on the far side); a split-horizon DNS setup gives the two
 * different answers on purpose. In all three the public URL is still correct for people and still
 * wrong for the daemon, so the bootstrap endpoints let the operator say where the node should
 * look instead.
 *
 * Whatever is accepted here ends up as the node's `--pano` argument, as its `PANO_URL`, and as
 * the host the install script downloads the daemon from — so it is checked rather than passed
 * through: an http(s) URL with a real host, nothing longer than a hostname can be, and normalized
 * the same way `website-url` is so that the same address written two ways is one address.
 */
object PanoUrlOverride {
    /** Comfortably past the longest legal host plus a scheme, port and path. */
    const val MAX_LENGTH = 255

    /**
     * The normalized override, or null when none was given.
     *
     * A value that was given but cannot be used is a [BadRequest] rather than a silent fallback
     * to the website URL: an operator who typed the tunnel address wrong needs to hear about it
     * now, not in ten minutes when the node never pairs.
     */
    fun sanitize(raw: String?): String? {
        val trimmed = raw?.trim().orEmpty()

        if (trimmed.isEmpty()) {
            return null
        }

        if (trimmed.length > MAX_LENGTH) {
            throw BadRequest()
        }

        val scheme = trimmed.substringBefore("://", missingDelimiterValue = "").lowercase()

        // Only http(s). This string becomes an argument on somebody else's machine, and every
        // other scheme there is either a typo or an attempt to be clever with one.
        if (scheme != "http" && scheme != "https") {
            throw BadRequest()
        }

        // The host is read from what was typed rather than from the normalized form, because
        // normalization falls back to returning the input unchanged when it cannot parse it, and
        // "http://" with nothing after it would sail through that.
        if (WebsiteUrlUtil.host(trimmed).isNullOrBlank()) {
            throw BadRequest()
        }

        return WebsiteUrlUtil.normalize(trimmed)
    }
}
