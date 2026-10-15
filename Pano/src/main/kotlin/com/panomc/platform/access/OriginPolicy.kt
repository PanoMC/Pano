package com.panomc.platform.access

import com.panomc.platform.config.ConfigManager
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.SystemProperty
import com.panomc.platform.error.InvalidFields
import com.panomc.platform.model.Error
import com.panomc.platform.util.DevMode
import com.panomc.platform.util.RegistrableDomain
import com.panomc.platform.util.TrustedProxyIpResolver
import com.panomc.platform.util.WebsiteUrlUtil
import io.vertx.core.http.HttpServerRequest
import io.vertx.core.json.JsonArray
import io.vertx.sqlclient.SqlClient
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Lazy
import org.springframework.stereotype.Component
import java.net.URI

/** The origin is on another registrable domain than the site: a different domain uses a front-end key (doc 05 §5). */
class OriginDifferentSite : Error("ORIGIN_DIFFERENT_SITE", 400, "Bad Request")

/** One route URL as a matcher: `:name` matches one segment, a trailing `*` matches the rest, the rest is literal. */
internal class AnyOriginPattern(val pattern: String) {
    private val regex: Regex = Regex(
        pattern.trimEnd('/').split('/').joinToString("/") { segment ->
            when {
                segment == "*" -> ".*"
                segment.startsWith(":") -> "[^/]+"
                else -> Regex.escape(segment)
            }
        } + "/?"
    )

    fun matches(path: String): Boolean = regex.matches(path)
}

/** The panel already lists [OriginPolicy.MAX_ORIGINS] allowed origins. */
class OriginLimitReached : Error("ORIGIN_LIMIT_REACHED", 400, "Bad Request")

/**
 * Allowed origins and the class of a request's `Origin` (open front-end plan, doc 05 §5).
 *
 * The list is the system property `allowed_origins` (a JSON array of `scheme://host[:port]`), cached
 * here. A browser front-end on the same site as Pano (same registrable domain) is put on it in the panel;
 * a front-end on another domain is a server and uses a front-end key instead.
 *
 * [classify] compares **hosts only** (scheme, port and path are ignored): the scope of a host-only
 * `SameSite=Lax` cookie, and what lets a TLS-terminating proxy and alias hosts work. Only the allowed
 * list itself matches the exact `scheme://host[:port]`.
 *
 * The primary constructor takes plain functions so the policy runs without Spring or a database; the
 * component constructor wires the real property store and config.
 */
@Lazy
@Component
class OriginPolicy(
    private val read: suspend (SqlClient) -> String?,
    private val write: suspend (SqlClient, String) -> Unit,
    private val websiteUrl: () -> String = { "" },
    private val siteUrl: () -> String = { "" },
    private val trustedProxies: () -> List<String> = { emptyList() },
    private val devMode: () -> Boolean = { false }
) {
    @Autowired
    constructor(databaseManager: DatabaseManager, configManager: ConfigManager) : this(
        read = { sql -> databaseManager.systemPropertyDao.getByOption(PROPERTY, sql)?.value },
        write = { sql, json ->
            if (databaseManager.systemPropertyDao.existsByOption(PROPERTY, sql)) {
                databaseManager.systemPropertyDao.update(PROPERTY, json, sql)
            } else {
                databaseManager.systemPropertyDao.add(SystemProperty(option = PROPERTY, value = json), sql)
            }
        },
        websiteUrl = { configManager.config.websiteUrl },
        siteUrl = { configManager.config.effectiveFrontend.siteUrl },
        trustedProxies = {
            try {
                configManager.config.server.trustedProxies
            } catch (_: Throwable) {
                emptyList()
            }
        },
        devMode = {
            try {
                DevMode.isActive(configManager.config)
            } catch (_: Throwable) {
                DevMode.isActive(null)
            }
        }
    )

    @Volatile
    private var cache: Set<String>? = null

    private val mutex = Mutex()

    private val anyOrigin = java.util.concurrent.ConcurrentHashMap<String, List<AnyOriginPattern>>()

    /**
     * The URL patterns of the routes that declare `browserAccess = ANY_ORIGIN` (doc 05 §4), per owner (`core` or a
     * plugin id), so an unloaded plugin takes its own paths away. A path may contain `:param` segments and a trailing `*`.
     */
    val anyOriginPaths: Set<String> get() = anyOrigin.values.flatten().mapTo(LinkedHashSet()) { it.pattern }

    /** Replaces the ANY_ORIGIN paths of [owner] (an empty collection removes them). */
    fun setAnyOriginPaths(owner: String, paths: Collection<String>) {
        if (paths.isEmpty()) {
            anyOrigin.remove(owner)
        } else {
            anyOrigin[owner] = paths.map { AnyOriginPattern(it) }
        }
    }

    /** Adds ANY_ORIGIN paths to [owner] (core routes are applied in one call, a plugin's in another). */
    fun addAnyOriginPaths(owner: String, paths: Collection<String>) {
        if (paths.isEmpty()) return

        anyOrigin.merge(owner, paths.map { AnyOriginPattern(it) }) { old, added -> old + added }
    }

    /** Removes everything [owner] registered (plugin unload). */
    fun removeAnyOriginPaths(owner: String) {
        anyOrigin.remove(owner)
    }

    /** Whether [path] (a request path, query not included) belongs to an ANY_ORIGIN route. */
    fun isAnyOriginPath(path: String): Boolean = anyOrigin.values.any { list -> list.any { it.matches(path) } }

    /** True once the list was read from the property (or set in this process). */
    val isLoaded: Boolean get() = cache != null

    /** Reads the property once; later calls return at once. A broken value counts as an empty list. */
    suspend fun ensureLoaded(sqlClient: SqlClient) {
        if (cache != null) return

        mutex.withLock {
            if (cache != null) return

            cache = parseStored(read(sqlClient))
        }
    }

    /** The allowed origins, in the order they were saved. */
    suspend fun list(sqlClient: SqlClient): List<String> {
        ensureLoaded(sqlClient)

        return cache.orEmpty().toList()
    }

    /** The cached list (empty before [ensureLoaded]). */
    fun snapshot(): Set<String> = cache.orEmpty()

    /**
     * Validates [raw] and saves it as the whole list. Throws [InvalidFields] (`origins`), [OriginDifferentSite]
     * or [OriginLimitReached]; nothing is saved then. [fallbackHost] is the site host when `website-url` is empty.
     */
    suspend fun replace(raw: List<String>, fallbackHost: String?, sqlClient: SqlClient): List<String> {
        val siteHost = WebsiteUrlUtil.host(websiteUrl()) ?: fallbackHost?.let { hostOfAuthority(it) }

        val normalized = validateAll(raw, siteHost)

        mutex.withLock {
            write(sqlClient, JsonArray(normalized).encode())

            cache = normalized.toCollection(LinkedHashSet())
        }

        return normalized
    }

    /** The class of the `Origin` of [request] (doc 05 §5). */
    fun classify(request: HttpServerRequest): OriginClass {
        val origin = request.getHeader("Origin") ?: return OriginClass.NONE

        val trusted = try {
            TrustedProxyIpResolver.isTrustedPeer(request.remoteAddress()?.host().orEmpty(), trustedProxies())
        } catch (_: Throwable) {
            false
        }

        val siteHosts = listOfNotNull(WebsiteUrlUtil.host(websiteUrl()), WebsiteUrlUtil.host(siteUrl()))

        return classify(
            origin = origin,
            hostHeader = request.getHeader("Host"),
            forwardedHost = request.getHeader("X-Forwarded-Host"),
            trustedPeer = trusted,
            siteHosts = siteHosts,
            allowed = snapshot(),
            devMode = devMode()
        )
    }

    companion object {
        /** The system property that holds the list (a JSON array of origins). */
        const val PROPERTY = "allowed_origins"

        const val MAX_ORIGINS = 20

        private val logger = LoggerFactory.getLogger(OriginPolicy::class.java)

        /** A policy with an empty list that reads nothing and saves nothing: every cross-host origin is `FOREIGN`. */
        fun empty(): OriginPolicy = OriginPolicy({ null }, { _, _ -> }).also { it.cache = emptySet() }

        /**
         * The pure rule of doc 05 §5. [origin] is the `Origin` header value; [hostHeader] the `Host` header;
         * [forwardedHost] the `X-Forwarded-Host` header, believed only when [trustedPeer]; [siteHosts] the
         * `website-url` and `frontend.site-url` hosts; [allowed] normalized `scheme://host[:port]` entries.
         */
        fun classify(
            origin: String?,
            hostHeader: String?,
            forwardedHost: String?,
            trustedPeer: Boolean,
            siteHosts: Collection<String>,
            allowed: Collection<String>,
            devMode: Boolean
        ): OriginClass {
            if (origin == null) return OriginClass.NONE

            val parsed = parseOrigin(origin) ?: return OriginClass.FOREIGN

            val sameHosts = buildList {
                hostOfAuthority(hostHeader)?.let { add(it) }

                if (trustedPeer) {
                    hostOfAuthority(forwardedHost?.split(",")?.firstOrNull())?.let { add(it) }
                }

                siteHosts.forEach { host -> cleanHost(host)?.let { add(it) } }
            }

            if (parsed.host in sameHosts) return OriginClass.SAME

            if (parsed.normalized in allowed) return OriginClass.ALLOWED

            if (devMode && isLocalhost(parsed.host)) return OriginClass.ALLOWED

            return OriginClass.FOREIGN
        }

        /** A parsed, normalized origin: lower-case scheme and host, the default port dropped. */
        internal data class Parsed(val scheme: String, val host: String, val port: Int, val normalized: String)

        /**
         * [raw] as an origin, or `null` when it is not an `http` / `https` URL with a host. Any path is
         * ignored here (a browser never sends one, a hostile client might); [validate] refuses it.
         */
        internal fun parseOrigin(raw: String): Parsed? {
            val uri = try {
                URI(raw.trim())
            } catch (_: Exception) {
                return null
            }

            val scheme = uri.scheme?.lowercase() ?: return null

            if (scheme != "http" && scheme != "https") return null

            val host = cleanHost(uri.host) ?: return null

            val defaultPort = if (scheme == "https") 443 else 80
            val port = uri.port.takeIf { it != -1 && it != defaultPort } ?: -1

            val shown = if (host.contains(':')) "[$host]" else host

            return Parsed(scheme, host, port, "$scheme://$shown" + if (port != -1) ":$port" else "")
        }

        /** Validates one entry (doc 05 §5 "Validation") and returns it normalized. */
        internal fun validate(raw: String, siteHost: String?): String {
            val value = raw.trim().removeSuffix("/")

            val invalid = InvalidFields(mapOf("origins" to "INVALID_ORIGIN"))

            if (value.isEmpty() || value.contains('*')) throw invalid

            val uri = try {
                URI(value)
            } catch (_: Exception) {
                throw invalid
            }

            if (!uri.rawPath.isNullOrEmpty() || uri.rawQuery != null || uri.rawFragment != null || uri.rawUserInfo != null) {
                throw invalid
            }

            val parsed = parseOrigin(value) ?: throw invalid

            val secureRequired = parsed.host != "localhost" && !WebsiteUrlUtil.isIpLiteral(parsed.host)

            if (parsed.scheme != "https" && secureRequired) throw invalid

            if (siteHost == null || !RegistrableDomain.sameSite(parsed.host, siteHost)) throw OriginDifferentSite()

            return parsed.normalized
        }

        private fun validateAll(raw: List<String>, siteHost: String?): List<String> {
            val result = LinkedHashSet<String>()

            raw.forEach { result += validate(it, siteHost) }

            if (result.size > MAX_ORIGINS) throw OriginLimitReached()

            return result.toList()
        }

        /** The stored JSON array as a set; an invalid entry (or value) is dropped with a warning. */
        internal fun parseStored(json: String?): Set<String> {
            if (json.isNullOrBlank()) return emptySet()

            return try {
                JsonArray(json).mapNotNull { (it as? String)?.let { entry -> parseOrigin(entry)?.normalized } }
                    .toCollection(LinkedHashSet())
            } catch (e: Exception) {
                logger.warn("The system property {} is not a JSON array of origins: {}", PROPERTY, e.message)

                emptySet()
            }
        }

        /** The host of a `Host`-style value (`host`, `host:port`, `[v6]:port`), lower case, without brackets. */
        internal fun hostOfAuthority(authority: String?): String? {
            val value = authority?.trim().orEmpty()

            if (value.isEmpty()) return null

            val host = if (value.startsWith("[")) {
                value.substringBefore(']').removePrefix("[")
            } else if (value.count { it == ':' } == 1) {
                value.substringBefore(':')
            } else {
                value
            }

            return cleanHost(host)
        }

        private fun cleanHost(host: String?): String? =
            host?.trim()?.lowercase()?.removePrefix("[")?.removeSuffix("]")?.trimEnd('.')?.takeIf { it.isNotEmpty() }

        private fun isLocalhost(host: String): Boolean =
            host == "localhost" || host.endsWith(".localhost") || host == "127.0.0.1" || host == "::1"
    }
}
