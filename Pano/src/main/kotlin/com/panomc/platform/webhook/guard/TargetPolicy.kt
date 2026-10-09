package com.panomc.platform.webhook.guard

import com.panomc.platform.webhook.guard.UrlGuard
import java.net.InetAddress

/**
 * The SSRF policy of outbound webhooks (00 section 8.6, 08 section 15.6, 11 section 7; test seam S10 of 17): a pure
 * function of the URL, the addresses the host name resolved to (the caller resolves, tests pass a fixed list) and the
 * effective `webhooks.allow-private-targets`. Used on save and again before every send.
 *
 * Even with the flag on, link-local (`169.254/16`, `fe80::/10`, `fd00:ec2::/32`), cloud metadata, unspecified,
 * broadcast and multicast addresses are refused, and so is every name that resolves to one of them: **every**
 * returned address must be allowed.
 */
object TargetPolicy {
    sealed class Result {
        /** [addresses] are the validated addresses in resolver order; the connection goes to the first one. */
        class Allowed(val target: UrlGuard.Target, val addresses: List<InetAddress>) : Result()

        class Refused(val reason: UrlGuard.Reason) : Result() {
            /** `lastError` of a delivery row refused at send time. */
            val lastError: String get() = lastErrorOf(reason)
        }

        val allowed: Allowed? get() = this as? Allowed
    }

    /**
     * [resolved] is the list the resolver returned for the host of [url]; `null` means resolution failed (a
     * [UrlGuard.Reason.DNS] refusal). It is ignored for an IP literal.
     */
    fun check(url: String, resolved: List<InetAddress>?, allowPrivate: Boolean, discord: Boolean = false): Result {
        val verdict = UrlGuard.validate(url, allowPrivate, discord)
        val target = verdict.target ?: return Result.Refused(verdict.reason!!)

        val literal = target.literal
        val addresses = if (literal != null) listOf(literal) else resolved ?: emptyList()
        val refusal = UrlGuard.checkAddresses(addresses, allowPrivate)

        return if (refusal == null) Result.Allowed(target, addresses) else Result.Refused(refusal)
    }

    /** `true` when [url] passes the syntax rules and the IP-literal checks (no resolution): the hook for `ActionParser`. */
    fun syntaxOk(url: String, allowPrivate: Boolean, discord: Boolean = false): Boolean = UrlGuard.validate(url, allowPrivate, discord).ok

    /** The flag is forced off when the instance is hosted (11 section 7.1). */
    fun effectiveAllowPrivate(flag: Boolean, hosted: Boolean): Boolean = flag && !hosted

    fun lastErrorOf(reason: UrlGuard.Reason): String = "$LAST_ERROR_PREFIX${reason.name}"

    const val LAST_ERROR_PREFIX = "URL_GUARD:"
}
