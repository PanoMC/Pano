package com.panomc.platform.access

import com.panomc.platform.UIManager
import com.panomc.platform.config.ConfigManager
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.model.Error
import com.panomc.platform.ui.FrontendMode
import com.panomc.platform.util.TrustedProxyIpResolver
import io.vertx.core.Handler
import io.vertx.core.http.HttpMethod
import io.vertx.ext.web.RoutingContext
import io.vertx.kotlin.coroutines.dispatcher
import io.vertx.sqlclient.SqlClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.context.annotation.Lazy
import org.springframework.context.annotation.Scope
import org.springframework.stereotype.Component
import java.net.InetAddress
import java.net.Inet6Address

/** `X-Pano-Frontend-Key` was sent and is unknown or revoked. Never treated as anonymous. */
class InvalidFrontendKey : Error("INVALID_FRONTEND_KEY", 401, "Unauthorized")

/**
 * Headless access is off while the front-end is a theme (`frontend.mode` THEME): a stored front-end key is refused.
 * Existing keys are kept and work again in another mode; the internal key (the theme's own) is never refused.
 */
class FrontendAccessDisabled : Error(
    "FRONTEND_ACCESS_DISABLED",
    403,
    "Forbidden",
    mapOf(
        "message" to "Headless access is off while the front-end is a theme; " +
            "choose another front-end under Panel → Appearance → Themes → Site display settings."
    )
) {
    companion object {
        /** The panel call that creates a key: refused while the front-end is a theme. */
        fun requireOff(mode: com.panomc.platform.ui.FrontendMode) {
            if (mode == com.panomc.platform.ui.FrontendMode.THEME) throw FrontendAccessDisabled()
        }
    }
}

/** `X-Pano-Client-Ip` from a valid key is not exactly one IP literal. */
class InvalidClientIp : Error("INVALID_CLIENT_IP", 400, "Bad Request")

/**
 * An unsafe request from a browser page of a foreign origin, without a front-end key, to a route that is not
 * `ANY_ORIGIN` (login CSRF and every session-less mutation, doc 05 §4): `403 ORIGIN_NOT_ALLOWED`. The message names the
 * origin and where the site owner allows it.
 */
class OriginNotAllowed(origin: String?) : Error(
    "ORIGIN_NOT_ALLOWED",
    403,
    "Forbidden",
    mapOf(
        "message" to "Requests that change data are not accepted from the origin '${origin.orEmpty()}'. " +
            "To allow it, add it under Panel → Appearance → Themes → Site display settings; " +
            "a front-end on another domain uses a front-end key from its server."
    )
)

/** A preflight from an origin that is not allowed (or a keyed one): `403 ORIGIN_NOT_ALLOWED` (doc 05 §5). */
private class PreflightNotAllowed : Error("ORIGIN_NOT_ALLOWED", 403, "Forbidden")

/**
 * The first handler of every API request (doc 05 §3.2, order 0, registered before the rate limiter):
 *
 * 1. matches the `X-Pano-Frontend-Key` header: `Invalid` answers `401 INVALID_FRONTEND_KEY`;
 * 2. resolves the client IP once: with a valid key the `X-Pano-Client-Ip` value (one IP literal,
 *    else `400 INVALID_CLIENT_IP`), otherwise the shipped resolver ([TrustedProxyIpResolver]); the
 *    header is never read without a valid key;
 * 3. classifies the `Origin` ([OriginPolicy], doc 05 §5), answers a CORS preflight (`204` for an allowed
 *    origin, else `403`) and adds the credentialed CORS headers for `ALLOWED` only (never `*`, none for
 *    a keyed request);
 * 4. stores an [AccessContext] under [AccessContext.CONTEXT_KEY] for the limiter, `getRemoteIP` and
 *    everything after it.
 *
 * A request without the key header and without an `Origin` never touches the database or a coroutine.
 */
@Lazy
@Component
@Scope(value = ConfigurableBeanFactory.SCOPE_SINGLETON)
class AccessPlaneHandler(
    private val frontendKeyService: FrontendKeyService,
    private val databaseManager: DatabaseManager,
    private val configManager: ConfigManager,
    private val originPolicy: OriginPolicy,
    private val uiManager: ObjectProvider<UIManager>
) {
    fun create(): Handler<RoutingContext> = create(
        frontendKeyService,
        sqlClient = { databaseManager.getSqlClient() },
        trustedProxies = {
            try {
                configManager.config.server.trustedProxies
            } catch (_: Throwable) {
                // Config not loaded yet (or the block was hand-deleted): treat every request as direct.
                emptyList()
            }
        },
        origins = originPolicy,
        // Read per request from the live value: a mode change applies without a restart.
        frontendMode = { uiManager.getObject().frontendMode }
    )

    companion object {
        const val CLIENT_IP_HEADER = "X-Pano-Client-Ip"

        private val SAFE_METHODS = setOf(HttpMethod.GET, HttpMethod.HEAD, HttpMethod.OPTIONS)

        private const val EXPOSED_HEADERS = "X-RateLimit-Limit, X-RateLimit-Remaining, Retry-After"
        private const val PREFLIGHT_METHODS = "GET,POST,PUT,PATCH,DELETE"
        private const val PREFLIGHT_HEADERS = "content-type, accept, x-csrf-token, x-requested-with"
        private const val PREFLIGHT_MAX_AGE = "600"

        private val logger = LoggerFactory.getLogger(AccessPlaneHandler::class.java)

        private val IPV4 = Regex("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$")
        private val IPV6_CHARS = Regex("^[0-9a-fA-F:.]{2,45}$")

        /** The handler itself; [create] on the component wires the real service, database and config. */
        fun create(
            service: FrontendKeyService,
            sqlClient: suspend () -> SqlClient,
            trustedProxies: () -> List<String>,
            origins: OriginPolicy = OriginPolicy.empty(),
            frontendMode: () -> FrontendMode = { FrontendMode.CUSTOM_APP }
        ): Handler<RoutingContext> = Handler { context ->
            // The list of allowed origins is only needed when an Origin header is there; read it once.
            if (!origins.isLoaded && context.request().getHeader("Origin") != null) {
                context.request().pause()

                CoroutineScope(context.vertx().dispatcher()).launch {
                    try {
                        origins.ensureLoaded(sqlClient())
                    } catch (e: Throwable) {
                        logger.warn("Could not load the allowed origins: {}", e.message)
                    }

                    context.request().resume()

                    try {
                        dispatch(context, service, sqlClient, trustedProxies, origins, frontendMode)
                    } catch (e: Throwable) {
                        context.fail(e)
                    }
                }

                return@Handler
            }

            dispatch(context, service, sqlClient, trustedProxies, origins, frontendMode)
        }

        private fun dispatch(
            context: RoutingContext,
            service: FrontendKeyService,
            sqlClient: suspend () -> SqlClient,
            trustedProxies: () -> List<String>,
            origins: OriginPolicy,
            frontendMode: () -> FrontendMode
        ) {
            val header = context.request().getHeader(FrontendKeyService.HEADER)

            if (header.isNullOrBlank()) {
                proceed(context, service, KeyMatch.None, trustedProxies, sqlClient, origins, frontendMode)

                return
            }

            val match = service.matchHeader(header)

            // A valid key (the internal one, or a loaded stored one) needs neither the database nor a coroutine.
            if (match is KeyMatch.Valid) {
                proceed(context, service, match, trustedProxies, sqlClient, origins, frontendMode)

                return
            }

            // Unknown to the in-memory map: it may simply not be loaded yet. Hold the request body while we wait.
            context.request().pause()

            CoroutineScope(context.vertx().dispatcher()).launch {
                try {
                    try {
                        service.ensureLoaded(sqlClient())
                    } catch (e: Throwable) {
                        logger.warn("Could not load the front-end keys: {}", e.message)
                    }

                    context.request().resume()

                    proceed(context, service, service.matchHeader(header), trustedProxies, sqlClient, origins, frontendMode)
                } catch (e: Throwable) {
                    context.fail(e)
                }
            }
        }

        private fun proceed(
            context: RoutingContext,
            service: FrontendKeyService,
            match: KeyMatch,
            trustedProxies: () -> List<String>,
            sqlClient: suspend () -> SqlClient,
            origins: OriginPolicy,
            frontendMode: () -> FrontendMode
        ) {
            val request = context.request()

            when (match) {
                KeyMatch.Invalid -> respond(context, InvalidFrontendKey())

                KeyMatch.None -> {
                    val ip = TrustedProxyIpResolver.resolveClientIp(request, trustedProxies())

                    val origin = origins.classify(request)

                    context.put(AccessContext.CONTEXT_KEY, AccessContext(key = null, clientIp = ip, origin = origin))

                    if (isPreflight(request)) {
                        answerPreflight(context, origin)

                        return
                    }

                    addCorsHeaders(context, origin)

                    // The Origin gate (doc 05 §4): a foreign page may not change anything, whether or not a session
                    // exists -- login CSRF is the case that has no session to check. A route that takes third-party
                    // posts declares ANY_ORIGIN; a keyed request is a server and never gets here.
                    if (origin == OriginClass.FOREIGN &&
                        request.method() !in SAFE_METHODS &&
                        !origins.isAnyOriginPath(context.normalizedPath())
                    ) {
                        respond(context, OriginNotAllowed(request.getHeader("Origin")))

                        return
                    }

                    context.next()
                }

                is KeyMatch.Valid -> {
                    // Only the selected front-end may be used: while Pano serves a theme, a stored key is refused.
                    if (!match.ref.isInternal && frontendMode() == FrontendMode.THEME) {
                        respond(context, FrontendAccessDisabled())

                        return
                    }

                    val declared = request.headers().getAll(CLIENT_IP_HEADER).filter { it.isNotBlank() }

                    val ip: String
                    val fromKey: Boolean

                    if (declared.isEmpty()) {
                        ip = TrustedProxyIpResolver.resolveClientIp(request, trustedProxies())
                        fromKey = false
                    } else {
                        ip = declared.singleOrNull()?.let { parseIpLiteral(it) } ?: run {
                            respond(context, InvalidClientIp())

                            return
                        }
                        fromKey = true
                    }

                    val origin = origins.classify(request)

                    context.put(AccessContext.CONTEXT_KEY, AccessContext(match.ref, ip, origin, fromKey))

                    if (!match.ref.isInternal) {
                        touch(context, service, match.ref, sqlClient)
                    }

                    // A keyed request is a server: it skips the Origin gate and gets no CORS headers (doc 05 §3, §5).
                    if (isPreflight(request)) {
                        respond(context, PreflightNotAllowed())

                        return
                    }

                    context.next()
                }
            }
        }

        /** `OPTIONS` with `Access-Control-Request-Method`: what a browser sends before a credentialed cross-origin call. */
        private fun isPreflight(request: io.vertx.core.http.HttpServerRequest): Boolean =
            request.method() == HttpMethod.OPTIONS && request.getHeader("Access-Control-Request-Method") != null

        /** `204` with the CORS headers for an `ALLOWED` origin, `403 ORIGIN_NOT_ALLOWED` for every other one. */
        private fun answerPreflight(context: RoutingContext, origin: OriginClass) {
            if (origin != OriginClass.ALLOWED) {
                respond(context, PreflightNotAllowed())

                return
            }

            addCorsHeaders(context, origin)

            context.response()
                .putHeader("Access-Control-Allow-Methods", PREFLIGHT_METHODS)
                .putHeader("Access-Control-Allow-Headers", PREFLIGHT_HEADERS)
                .putHeader("Access-Control-Max-Age", PREFLIGHT_MAX_AGE)
                .setStatusCode(204)
                .end()
        }

        /**
         * The credentialed CORS headers for an `ALLOWED` origin: that origin itself (never `*`), credentials,
         * `Vary: Origin` and the exposed rate-limit headers. Any other class gets none, only `Vary: Origin`
         * so that no cache serves a header-less copy to an allowed origin.
         */
        private fun addCorsHeaders(context: RoutingContext, origin: OriginClass) {
            val request = context.request()
            val value = request.getHeader("Origin") ?: return
            val headers = context.response().headers()

            headers.add("Vary", "Origin")

            if (origin != OriginClass.ALLOWED) return

            headers.set("Access-Control-Allow-Origin", value)
            headers.set("Access-Control-Allow-Credentials", "true")
            headers.set("Access-Control-Expose-Headers", EXPOSED_HEADERS)
        }

        /** `lastUsedAt`, off the request path; the service writes at most once a minute per key. */
        private fun touch(
            context: RoutingContext,
            service: FrontendKeyService,
            ref: FrontendKeyRef,
            sqlClient: suspend () -> SqlClient
        ) {
            CoroutineScope(context.vertx().dispatcher()).launch {
                try {
                    service.markUsed(ref, sqlClient())
                } catch (e: Throwable) {
                    logger.debug("Could not record the use of front-end key {}: {}", ref.id, e.message)
                }
            }
        }

        private fun respond(context: RoutingContext, error: Error) {
            val response = context.response()

            if (response.ended() || response.headWritten()) return

            response
                .putHeader("content-type", "application/json; charset=utf-8")
                .setStatusCode(error.getStatusCode())
                .setStatusMessage(error.getStatusMessage())
                .end(error.encode())
        }

        /**
         * [raw] as a canonical IP literal (IPv4 dotted quad, or IPv6 in hex with `:`), or `null`.
         * Exactly one address: no list, port, brackets, zone or host name; no DNS lookup happens.
         */
        internal fun parseIpLiteral(raw: String): String? {
            val value = raw.trim()

            IPV4.matchEntire(value)?.let { match ->
                return if (match.groupValues.drop(1).all { it.toInt() in 0..255 }) {
                    match.groupValues.drop(1).joinToString(".") { it.toInt().toString() }
                } else {
                    null
                }
            }

            // A string with a colon is parsed as IPv6 by the JDK and never resolved by name.
            if (!value.contains(':') || !IPV6_CHARS.matches(value)) return null

            return try {
                val address = InetAddress.getByName(value)

                if (address is Inet6Address) {
                    TrustedProxyIpResolver.normalizeIp(value)
                } else {
                    // ::ffff:a.b.c.d is an IPv4 address in disguise: one canonical form per client.
                    address.hostAddress
                }
            } catch (_: Exception) {
                null
            }
        }
    }
}
