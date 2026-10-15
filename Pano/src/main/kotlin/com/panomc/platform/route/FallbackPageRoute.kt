package com.panomc.platform.route

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.frontend.FallbackPageEnvironment
import com.panomc.platform.frontend.FallbackPageRegistry
import com.panomc.platform.frontend.FrontendUrlMap
import com.panomc.platform.frontend.ThemeRouteMap
import com.panomc.platform.frontend.pages.FallbackPageRenderer
import com.panomc.platform.model.Path
import com.panomc.platform.model.RouteType
import com.panomc.platform.model.Template
import com.panomc.platform.util.UsageMode
import io.vertx.core.Handler
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.json.schema.SchemaRepository
import io.vertx.kotlin.coroutines.dispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.slf4j.Logger
import java.security.MessageDigest

/**
 * `GET /_pano/:target`, the built-in fallback pages (doc 05 section 10.3), and their two assets
 * `/_pano/assets/fallback.js` and `fallback.css`.
 *
 * - unknown target (or a site that is not set up, or a servers-only install): `404`;
 * - maintenance mode on and the visitor not let through (the check of `Api.checkMaintenance`): `302 /`;
 * - the target taken over by a front-end (the URL map no longer answers with `/_pano/<id>`): `302` there,
 *   the query string kept;
 * - otherwise the page, rendered in the shell `resources/fallback/shell.hbs`.
 *
 * The route sits at order 1 like every endpoint, so the maintenance gate (order 2) never sees it; the
 * maintenance check is made here.
 */
@Endpoint
class FallbackPageRoute(
    private val registry: FallbackPageRegistry,
    private val frontendUrlMap: FrontendUrlMap,
    private val renderer: FallbackPageRenderer,
    private val environment: FallbackPageEnvironment,
    private val logger: Logger
) : Template() {
    override val order = 1

    override val usageModes = UsageMode.WITH_WEBSITE

    override val paths = listOf(
        Path("/_pano/assets/fallback.js", RouteType.GET),
        Path("/_pano/assets/fallback.css", RouteType.GET),
        Path("/_pano/:target", RouteType.GET)
    )

    override fun bodyHandler(): Handler<RoutingContext>? = null

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler? = null

    override fun getHandler() = Handler<RoutingContext> { context ->
        when (context.normalizedPath()) {
            JS_PATH -> serveAsset(context, "fallback/fallback.js", "text/javascript; charset=utf-8")
            CSS_PATH -> serveAsset(context, "fallback/fallback.css", "text/css; charset=utf-8")

            else -> CoroutineScope(context.vertx().dispatcher()).launch {
                try {
                    servePage(context)
                } catch (t: Throwable) {
                    logger.error("Fallback page {} failed", context.normalizedPath(), t)

                    if (!context.response().ended()) {
                        context.response()
                            .setStatusCode(500)
                            .putHeader("Cache-Control", "no-store")
                            .putHeader("Content-Type", "text/plain; charset=utf-8")
                            .end("Something went wrong.")
                    }
                }
            }
        }
    }

    private suspend fun servePage(context: RoutingContext) {
        val response = context.response()
        val target = context.pathParam("target")

        val registered = if (target.isNullOrEmpty()) null else registry.find(target)

        if (registered == null || !environment.ready()) {
            notFound(context)

            return
        }

        if (environment.blockedByMaintenance(context)) {
            redirect(context, "/")

            return
        }

        if (takenOver(registered.id)) {
            redirect(context, takeoverLocation(registered.id, queryOf(context), context.request().query()))

            return
        }

        val html = renderer.render(registered, context)

        response
            .setStatusCode(200)
            .putHeader("Content-Type", "text/html; charset=utf-8")
            .putHeader("Cache-Control", "no-store")
            .putHeader("Referrer-Policy", "no-referrer")
            .putHeader("X-Content-Type-Options", "nosniff")
            .putHeader("X-Robots-Tag", "noindex, nofollow")
            .putHeader("Content-Security-Policy", CSP)
            .end(html)
    }

    /** True when a front-end (the admin's override, its `urls`, the theme's routes) answers for [id]. */
    private fun takenOver(id: String): Boolean {
        val location = try {
            frontendUrlMap.pathTemplate(id)
        } catch (e: Exception) {
            null
        }

        return location != null && !location.startsWith(FrontendUrlMap.FALLBACK_PREFIX)
    }

    /**
     * Where the taken-over target lives, with the request's query: the parameters the target names fill its
     * placeholders, the others are appended. A placeholder the request has no value for cannot be filled; the
     * visitor then gets the target's path with the query as it came.
     */
    private fun takeoverLocation(id: String, query: Map<String, String>, rawQuery: String?): String {
        try {
            frontendUrlMap.url(id, query)?.let { return it }
        } catch (e: IllegalArgumentException) {
            // fall through: the request has no value for a placeholder
        }

        val template = frontendUrlMap.template(id) ?: return "/"
        val bare = ThemeRouteMap.splitSuffix(template).first

        return if (rawQuery.isNullOrEmpty()) bare else "$bare?$rawQuery"
    }

    private fun queryOf(context: RoutingContext): Map<String, String> {
        val out = LinkedHashMap<String, String>()

        context.queryParams().entries().forEach { (name, value) -> out.putIfAbsent(name, value) }

        return out
    }

    private fun notFound(context: RoutingContext) {
        context.response()
            .setStatusCode(404)
            .putHeader("Cache-Control", "no-store")
            .putHeader("Content-Type", "text/plain; charset=utf-8")
            .end("Not found")
    }

    private fun redirect(context: RoutingContext, location: String) {
        context.response()
            .setStatusCode(302)
            .putHeader("Location", location)
            .putHeader("Cache-Control", "no-store")
            .putHeader("Referrer-Policy", "no-referrer")
            .end()
    }

    // --- assets ------------------------------------------------------------

    private fun serveAsset(context: RoutingContext, resource: String, contentType: String) {
        val asset = assets[resource] ?: load(resource)?.also { assets[resource] = it }
        val response = context.response()

        if (asset == null) {
            notFound(context)

            return
        }

        response.putHeader("ETag", asset.etag).putHeader("Cache-Control", "no-cache")

        if (context.request().getHeader("If-None-Match") == asset.etag) {
            response.setStatusCode(304).end()

            return
        }

        response
            .setStatusCode(200)
            .putHeader("Content-Type", contentType)
            .putHeader("X-Content-Type-Options", "nosniff")
            .end(io.vertx.core.buffer.Buffer.buffer(asset.bytes))
    }

    private fun load(resource: String): Asset? {
        val bytes = javaClass.classLoader.getResourceAsStream(resource)?.use { it.readBytes() } ?: return null
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)

        return Asset(bytes, "\"" + digest.take(8).joinToString("") { "%02x".format(it) } + "\"")
    }

    private class Asset(val bytes: ByteArray, val etag: String)

    private val assets = java.util.concurrent.ConcurrentHashMap<String, Asset>()

    companion object {
        const val PREFIX = "/_pano/"
        const val JS_PATH = FallbackPageRenderer.FALLBACK_JS_PATH
        const val CSS_PATH = FallbackPageRenderer.FALLBACK_CSS_PATH

        /**
         * Same-origin only: the page loads its own script and style, calls the API of the same host and shows the
         * site logo. Nothing may frame it (a sign-in form).
         */
        const val CSP = "default-src 'self'; img-src 'self' data:; base-uri 'none'; form-action 'self'; " +
            "frame-ancestors 'none'; object-src 'none'"
    }
}
