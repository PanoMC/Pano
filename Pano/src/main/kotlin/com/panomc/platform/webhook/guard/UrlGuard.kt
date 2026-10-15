package com.panomc.platform.webhook.guard

import java.net.IDN
import java.net.InetAddress
import java.net.URI

/**
 * The pure half of the SSRF guard for admin-configured URLs (11 section 7.2 steps 1 to 7, plus the classification of
 * resolved addresses of steps 8 and 9). Nothing here resolves a name or opens a socket: `OutboundHttp` resolves and hands
 * the addresses to [checkAddresses], so the same verdicts are produced at save time and before every send.
 *
 * [validate] never throws.
 */
object UrlGuard {
    const val MAX_URL_LENGTH = 1024

    /** The verdict names of 11 section 7.2: `reason` of `INVALID_WEBHOOK_URL`, `lastError = URL_GUARD:<reason>` at send time. */
    enum class Reason(val retryable: Boolean = false) {
        MALFORMED, SCHEME, USERINFO, HOST, PORT, DISCORD_URL, DNS(retryable = true), PRIVATE_ADDRESS
    }

    /** A URL that passed the syntax rules. [literal] is set when [host] is an IP literal (no resolution is needed). */
    class Target(val url: String, val scheme: String, val host: String, val port: Int, val literal: InetAddress?) {
        val https: Boolean get() = scheme == "https"

        override fun toString(): String = "$scheme://$host:$port"
    }

    /** Exactly one of [target] and [reason] is set. */
    class UrlVerdict private constructor(val target: Target?, val reason: Reason?) {
        val ok: Boolean get() = reason == null

        companion object {
            internal fun ok(target: Target) = UrlVerdict(target, null)
            internal fun refused(reason: Reason) = UrlVerdict(null, reason)
        }
    }

    /** Discord's own webhook path (an outbound URL, not a Pano route), compared without its leading slash. */
    private const val DISCORD_WEBHOOK_PATH = "api/webhooks/"
    private val DISCORD_HOSTS = setOf("discord.com", "discordapp.com", "ptb.discord.com", "canary.discord.com")
    private val REFUSED_SUFFIXES = listOf(".localhost", ".local", ".internal", ".lan", ".home.arpa")
    private val DOTTED_QUAD = Regex("""^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$""")
    private val NUMERIC_LOOKING = Regex("^[0-9a-fx.]+$")
    private val IPV6_CHARS = Regex("^[0-9a-fA-F:.]+$")

    /**
     * Steps 1 to 7. [allowPrivate] is the effective `webhooks.allow-private-targets` (false when hosted): it lets the names
     * `localhost`, `*.localhost`, `*.local`, `*.internal`, `*.lan` and `*.home.arpa` through (they are then classified
     * after resolution like any other name) and the loopback / private / reserved IP literals. Link-local, metadata,
     * unspecified, broadcast and multicast literals are refused with the flag as well. [discord] is `format == DISCORD`.
     */
    fun validate(url: String, allowPrivate: Boolean, discord: Boolean = false): UrlVerdict {
        if (url.length > MAX_URL_LENGTH || url.isEmpty()) return UrlVerdict.refused(Reason.MALFORMED)
        val uri = try {
            URI(url)
        } catch (e: Exception) {
            return UrlVerdict.refused(Reason.MALFORMED)
        }
        if (!uri.isAbsolute || uri.isOpaque) return UrlVerdict.refused(Reason.MALFORMED)

        val scheme = uri.scheme.lowercase()
        if (scheme != "http" && scheme != "https") return UrlVerdict.refused(Reason.SCHEME)
        if (uri.rawUserInfo != null) return UrlVerdict.refused(Reason.USERINFO)
        if (uri.rawFragment != null) return UrlVerdict.refused(Reason.MALFORMED)

        val rawHost = uri.host ?: return UrlVerdict.refused(if (uri.rawAuthority == null) Reason.MALFORMED else Reason.HOST)
        if (rawHost.isEmpty()) return UrlVerdict.refused(Reason.HOST)

        val literal: InetAddress?
        val host: String

        if (rawHost.startsWith("[")) {
            val inner = rawHost.removePrefix("[").removeSuffix("]")
            if (!rawHost.endsWith("]") || inner.isEmpty() || !IPV6_CHARS.matches(inner) || !inner.contains(':')) {
                return UrlVerdict.refused(Reason.HOST)
            }
            literal = try {
                InetAddress.getByName(inner) // a literal containing ':' is parsed, never resolved
            } catch (e: Exception) {
                return UrlVerdict.refused(Reason.HOST)
            }
            host = inner.lowercase()
        } else {
            val ascii = try {
                IDN.toASCII(rawHost, IDN.ALLOW_UNASSIGNED)
            } catch (e: Exception) {
                return UrlVerdict.refused(Reason.HOST)
            }.lowercase().removeSuffix(".")
            if (ascii.isEmpty() || ascii.startsWith(".") || ascii.contains("..")) return UrlVerdict.refused(Reason.HOST)
            host = ascii

            val quad = DOTTED_QUAD.matchEntire(host)
            if (quad != null) {
                val octets = quad.groupValues.drop(1)
                // 010.0.0.1 is read as octal by some stacks: only canonical decimal is accepted
                if (octets.any { (it.length > 1 && it.startsWith("0")) || it.toInt() > 255 }) return UrlVerdict.refused(Reason.HOST)
                literal = InetAddress.getByAddress(ByteArray(4) { octets[it].toInt().toByte() })
            } else {
                // 2130706433, 0x7f.1, 017700000001: numeric spellings of an address that is not a strict dotted quad
                if (NUMERIC_LOOKING.matches(host)) return UrlVerdict.refused(Reason.HOST)
                if (!allowPrivate && isLocalName(host)) return UrlVerdict.refused(Reason.HOST)
                literal = null
            }
        }

        val port = when {
            uri.port == -1 -> if (scheme == "https") 443 else 80
            uri.port == 80 || uri.port == 443 || uri.port in 1024..65535 -> uri.port
            else -> return UrlVerdict.refused(Reason.PORT)
        }

        if (discord) {
            if (host !in DISCORD_HOSTS || uri.rawPath?.removePrefix("/")?.startsWith(DISCORD_WEBHOOK_PATH) != true) return UrlVerdict.refused(Reason.DISCORD_URL)
        }

        if (literal != null && !AddressClass.of(literal).isAllowed(allowPrivate)) return UrlVerdict.refused(Reason.PRIVATE_ADDRESS)

        return UrlVerdict.ok(Target(url, scheme, host, port, literal))
    }

    /** Steps 8 and 9 on the addresses a name resolved to: `null` = every address is allowed; an empty list is a [Reason.DNS] failure. */
    fun checkAddresses(addresses: List<InetAddress>, allowPrivate: Boolean): Reason? {
        if (addresses.isEmpty()) return Reason.DNS
        return if (addresses.all { AddressClass.of(it).isAllowed(allowPrivate) }) null else Reason.PRIVATE_ADDRESS
    }

    private fun isLocalName(host: String): Boolean = host == "localhost" || REFUSED_SUFFIXES.any { host.endsWith(it) }
}
