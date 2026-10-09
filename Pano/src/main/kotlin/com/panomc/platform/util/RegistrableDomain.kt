package com.panomc.platform.util

import java.net.IDN

/**
 * The registrable domain ("eTLD+1") of a host, from the bundled Public Suffix List
 * (`resources/public_suffix_list.dat`, no dependency). It tells whether two hosts belong to one site,
 * which is the scope of a host-only `SameSite=Lax` cookie (open front-end plan, doc 05 §5).
 *
 * `of("play.example.com")` and `of("api.example.com")` are both `example.com`; `of("a.github.io")` is
 * `a.github.io` (a private suffix of the list); `of("evil.co.uk")` never equals `of("good.co.uk")`.
 * [of] returns `null` for an IP literal, `localhost` and a host that is itself a public suffix
 * (`com`, `co.uk`): [sameSite] then asks for the two hosts to be equal.
 */
object RegistrableDomain {
    private const val RESOURCE = "/public_suffix_list.dat"

    private class Rules(val plain: Set<String>, val wildcard: Set<String>, val exception: Set<String>)

    private val rules: Rules by lazy { load() }

    /** The registrable domain of [host] (lower case, ASCII), or `null` when it has none. */
    fun of(host: String?): String? {
        val value = clean(host) ?: return null

        if (value == "localhost" || WebsiteUrlUtil.isIpLiteral(value)) return null

        val labels = value.split('.')

        if (labels.any { it.isEmpty() }) return null

        val rules = rules

        // Number of labels of the public suffix (the Public Suffix List algorithm; the default rule is "*").
        var suffixLength = 1

        // An exception rule beats every other rule: its suffix is the rule minus its first label.
        val exceptionAt = labels.indices.firstOrNull { labels.subList(it, labels.size).joinToString(".") in rules.exception }

        if (exceptionAt != null) {
            suffixLength = labels.size - exceptionAt - 1
        } else {
            for (start in labels.indices) {
                val length = labels.size - start

                if (labels.subList(start, labels.size).joinToString(".") in rules.plain) {
                    suffixLength = maxOf(suffixLength, length)
                }

                if (length >= 2 && labels.subList(start + 1, labels.size).joinToString(".") in rules.wildcard) {
                    suffixLength = maxOf(suffixLength, length)
                }
            }
        }

        if (labels.size <= suffixLength) return null

        return labels.subList(labels.size - suffixLength - 1, labels.size).joinToString(".")
    }

    /**
     * True when [a] and [b] are one site: the same registrable domain, or, when either has none (an IP,
     * `localhost`, a public suffix), the same host.
     */
    fun sameSite(a: String?, b: String?): Boolean {
        val left = clean(a) ?: return false
        val right = clean(b) ?: return false

        val leftDomain = of(left)
        val rightDomain = of(right)

        if (leftDomain == null || rightDomain == null) return left == right

        return leftDomain == rightDomain
    }

    /** The host as compared: no brackets, no trailing dot, lower case, IDN in its ASCII form. */
    internal fun clean(host: String?): String? {
        var value = host?.trim()?.lowercase()?.removePrefix("[")?.removeSuffix("]") ?: return null

        value = value.trimEnd('.')

        if (value.isEmpty()) return null

        if (value.any { it.code > 127 }) {
            value = try {
                IDN.toASCII(value, IDN.ALLOW_UNASSIGNED).lowercase()
            } catch (_: Exception) {
                return null
            }
        }

        return value
    }

    private fun load(): Rules {
        val plain = HashSet<String>(16_000)
        val wildcard = HashSet<String>(256)
        val exception = HashSet<String>(16)

        val stream = RegistrableDomain::class.java.getResourceAsStream(RESOURCE)
            ?: error("$RESOURCE is missing from the Pano jar")

        stream.bufferedReader(Charsets.UTF_8).useLines { lines ->
            for (raw in lines) {
                val line = raw.trim()

                if (line.isEmpty() || line.startsWith("//")) continue

                val rule = line.split(Regex("\\s+"), limit = 2)[0]

                when {
                    rule.startsWith("!") -> exception += ascii(rule.substring(1))
                    rule.startsWith("*.") -> wildcard += ascii(rule.substring(2))
                    else -> plain += ascii(rule)
                }
            }
        }

        return Rules(plain, wildcard, exception)
    }

    private fun ascii(rule: String): String =
        if (rule.any { it.code > 127 }) {
            try {
                IDN.toASCII(rule, IDN.ALLOW_UNASSIGNED).lowercase()
            } catch (_: Exception) {
                rule.lowercase()
            }
        } else {
            rule.lowercase()
        }
}
