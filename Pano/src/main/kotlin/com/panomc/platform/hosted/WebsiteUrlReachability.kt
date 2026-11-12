package com.panomc.platform.hosted

import io.vertx.core.Handler
import io.vertx.core.Vertx
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.client.WebClient
import io.vertx.ext.web.client.WebClientOptions
import org.slf4j.Logger
import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.context.annotation.Lazy
import org.springframework.context.annotation.Scope
import org.springframework.stereotype.Component
import java.security.SecureRandom
import java.util.*
import java.util.concurrent.ConcurrentHashMap

/**
 * Pano Host: whether the host of `website-url` really reaches this instance. A customer can type any
 * domain into the installer or the panel; when that domain is not attached to the instance (no DNS, or
 * attached elsewhere), the canonical-host redirect would send every visitor to a dead site. Before
 * redirecting, the instance fetches `https://<host>[PATH]` and accepts the host only when it answers
 * this process's random [token]. Results are cached ([OK_TTL_MS] / [FAIL_TTL_MS]); an unknown host
 * counts as unreachable while its first probe runs, so a visitor is never bounced on a guess.
 */
@Lazy
@Component
@Scope(value = ConfigurableBeanFactory.SCOPE_SINGLETON)
class WebsiteUrlReachability(
    private val vertx: Vertx,
    private val logger: Logger
) {
    private data class Entry(val reachable: Boolean, val checkedAt: Long)

    private val token: String = ByteArray(24).also { SecureRandom().nextBytes(it) }
        .let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
    private val cache = ConcurrentHashMap<String, Entry>()
    private val inFlight: MutableSet<String> = ConcurrentHashMap.newKeySet()

    private val client by lazy {
        WebClient.create(
            vertx,
            WebClientOptions()
                .setFollowRedirects(false)
                .setConnectTimeout(TIMEOUT_MS)
                .setIdleTimeout(TIMEOUT_MS)
                .setIdleTimeoutUnit(java.util.concurrent.TimeUnit.MILLISECONDS)
                .setUserAgent("Pano-Host-Instance")
        )
    }

    /** True only when a recent probe of [host] reached this Pano; a missing or stale result starts a probe. */
    fun reachesThisPano(host: String): Boolean {
        val key = host.trim().lowercase(Locale.ROOT)
        val entry = cache[key]
        val ttl = if (entry?.reachable == true) OK_TTL_MS else FAIL_TTL_MS

        if (entry == null || System.currentTimeMillis() - entry.checkedAt > ttl) probe(key)

        return entry?.reachable == true
    }

    private fun probe(host: String) {
        if (!inFlight.add(host)) return

        try {
            client.getAbs("https://$host$PATH").timeout(TIMEOUT_MS.toLong()).send().onComplete { result ->
                val reachable = result.succeeded() &&
                        result.result().statusCode() == 200 &&
                        result.result().bodyAsString()?.trim() == token
                val previous = cache.put(host, Entry(reachable, System.currentTimeMillis()))

                inFlight.remove(host)

                if (previous?.reachable != reachable) {
                    if (reachable) logger.info("website-url host $host reaches this Pano: redirecting other hosts there")
                    else logger.warn("website-url host $host does not reach this Pano (domain not attached?): not redirecting other hosts there")
                }
            }
        } catch (e: Exception) {
            inFlight.remove(host)
            cache[host] = Entry(false, System.currentTimeMillis())
        }
    }

    /** Answers the probe with this process's token. */
    fun handler(): Handler<RoutingContext> = Handler { ctx ->
        ctx.response()
            .putHeader("Content-Type", "text/plain")
            .putHeader("Cache-Control", "no-store")
            .end(token)
    }

    companion object {
        const val PATH = "/.well-known/pano/instance-check"
        private const val TIMEOUT_MS = 5_000
        private const val OK_TTL_MS = 10 * 60_000L
        private const val FAIL_TTL_MS = 60_000L
    }
}
