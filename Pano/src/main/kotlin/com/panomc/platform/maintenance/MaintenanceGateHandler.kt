package com.panomc.platform.maintenance

import com.panomc.platform.UIManager
import com.panomc.platform.error.MaintenanceModeEnabled
import com.panomc.platform.maintenance.MaintenanceModeManager.Access
import com.panomc.platform.maintenance.MaintenanceModeManager.Notice
import com.panomc.platform.maintenance.MaintenanceModeManager.PathClass
import com.panomc.platform.model.Route
import io.vertx.core.Handler
import io.vertx.core.http.HttpMethod
import io.vertx.core.http.HttpServerRequest
import io.vertx.ext.web.RoutingContext
import io.vertx.kotlin.coroutines.dispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.slf4j.Logger
import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.context.annotation.Lazy
import org.springframework.context.annotation.Scope
import org.springframework.stereotype.Component

/**
 * The order-2 wildcard handler that answers every page request while maintenance mode is on.
 *
 * It only covers page traffic: `@Endpoint` handlers sit at order 1 and terminate without calling
 * `next()`, so an order-2 handler never sees the API routes. Those are gated in
 * `Api.checkMaintenance`.
 *
 * Registered as a raw router handler by [com.panomc.platform.route.RouterProvider], never as an
 * `@Endpoint`/`Template` bean — those get a default `BodyHandler`, and `ProxyHandlerImpl` fails any
 * proxied request with a 500 once a body handler has run.
 */
@Lazy
@Component
@Scope(value = ConfigurableBeanFactory.SCOPE_SINGLETON)
class MaintenanceGateHandler(
    private val maintenanceModeManager: MaintenanceModeManager,
    private val uiManager: UIManager,
    private val logger: Logger
) {
    fun create(): Handler<RoutingContext> = Handler { context ->
        // Fast path: a single field read, no pause, no coroutine, no allocation.
        if (!maintenanceModeManager.isEnabled()) {
            context.next()

            return@Handler
        }

        val path = context.normalizedPath()

        // Crawlers must be told the outage is temporary in the format they actually parse.
        if (path == ROBOTS_TXT_PATH) {
            endRobotsTxt(context)

            return@Handler
        }

        val pathClass = maintenanceModeManager.classify(path, context.request().method())

        if (pathClass == PathClass.INFRASTRUCTURE) {
            context.next()

            return@Handler
        }

        if (pathClass == PathClass.UNMATCHED_API) {
            endJson503(context)

            return@Handler
        }

        val request = context.request()

        // WebSocket upgrades and request bodies must stay unread across the suspension point.
        request.pause()

        CoroutineScope(context.vertx().dispatcher()).launch {
            try {
                val access = maintenanceModeManager.resolveAccess(context)

                if (context.response().closed()) {
                    return@launch
                }

                request.resume()

                if (pathClass == PathClass.PANEL) {
                    handlePanel(context, path, access)

                    return@launch
                }

                handlePage(context, path, access)
            } catch (t: Throwable) {
                logger.error("Maintenance gate failed on {}", path, t)

                if (!context.response().ended()) {
                    endPlain503(context)
                }
            }
        }
    }

    private suspend fun handlePanel(context: RoutingContext, path: String, access: Access) {
        // Only panel-access holders reach the order-4 proxy. UIManager's own /panel handler falls
        // through to the theme proxy for everyone else (UIManager.kt:1210-1225), which would leak
        // the theme during maintenance.
        if (access.hasPanelAccess) {
            context.next()

            return
        }

        if (!access.isLoggedIn && !maintenanceModeManager.isLoginLocation(path)) {
            // Deep links such as /panel/players must still lead somewhere; a dead-end 503 is the
            // worst place to strand an admin during maintenance.
            if (isSafeMethod(context.request().method())) {
                redirectToLoginLocation(context)
            } else {
                endPlain503(context)
            }

            return
        }

        serve(context, path, access)
    }

    private suspend fun handlePage(context: RoutingContext, path: String, access: Access) {
        if (!access.canBypass) {
            serve(context, path, access)

            return
        }

        // Subresources are gated by the permission, not by the cookie. An authorised user who was
        // just handed a theme page still has to be able to fetch its stylesheets, images and
        // client-side data, and their client-side navigation inside the theme never issues a
        // document request. The cookie only decides which UI a *navigation* gets.
        if (!isDocumentRequest(context.request())) {
            if (uiManager.activatedUIList[Route.Type.THEME_UI] == null) {
                endPlain503(context)
            } else {
                context.next()
            }

            return
        }

        if (!maintenanceModeManager.hasSkipCookie(context)) {
            serve(context, path, access)

            return
        }

        // Switching the theme transiently unbinds the order-5 route, and falling through then
        // lands on IndexTemplate's bare 401 — right in the middle of this feature's number one
        // use case. Serve our own page with a transient notice instead, and keep the skip so the
        // retry does not need a second click.
        if (uiManager.activatedUIList[Route.Type.THEME_UI] == null) {
            serve(context, path, access, Notice.THEME_SWITCHING)

            return
        }

        // One shot: the skip is spent by the navigation it was granted for, so a reload or a new
        // tab brings the maintenance page back and an admin cannot forget the site is closed.
        maintenanceModeManager.clearSkipCookie(context)

        context.next() // → order 5 → the real theme
    }

    /**
     * A top-level navigation, i.e. the thing a reload repeats. `Sec-Fetch-Dest` is sent by every
     * current browser; the `Accept` fallback covers the rest and command-line clients.
     */
    private fun isDocumentRequest(request: HttpServerRequest): Boolean {
        val destination = request.getHeader("Sec-Fetch-Dest")

        if (destination != null) {
            return destination.equals("document", ignoreCase = true) ||
                    destination.equals("iframe", ignoreCase = true)
        }

        if (!isSafeMethod(request.method())) {
            return false
        }

        return request.getHeader("Accept")?.contains("text/html", ignoreCase = true) == true
    }

    private suspend fun serve(
        context: RoutingContext,
        path: String,
        access: Access,
        forcedNotice: Notice? = null
    ) {
        val request = context.request()
        val method = request.method()

        if (!isSafeMethod(method)) {
            endPlain503(context)

            return
        }

        val banned = maintenanceModeManager.isBanned(maintenanceModeManager.resolveBanIdentity(context))
        val notice = forcedNotice ?: Notice.fromErrorCode(request.getParam(MaintenanceModeManager.ERROR_PARAM))
        val isLoginPage = !access.isLoggedIn && !banned && maintenanceModeManager.isLoginLocation(path)

        // Rendered before any status or header is written: renderLoginPage issues the double-submit
        // nonce as a Set-Cookie side effect.
        val html = if (isLoginPage) {
            maintenanceModeManager.renderLoginPage(context, notice)
        } else {
            maintenanceModeManager.renderMaintenancePage(
                context,
                access,
                notice ?: if (banned) Notice.IP_BANNED else null
            )
        }

        val response = context.response()

        if (response.ended() || response.headWritten()) {
            return
        }

        if (isLoginPage) {
            // 200, not 503: this is a working page, and browser form/password handling degrades on
            // an error status.
            response.statusCode = 200
        } else {
            response
                .setStatusCode(503)
                .putHeader("Retry-After", MaintenanceModeManager.RETRY_AFTER_SECONDS.toString())
        }

        response
            .putHeader("Content-Type", "text/html; charset=utf-8")
            .putHeader("Cache-Control", "no-store, no-cache, must-revalidate, max-age=0")
            .putHeader("Pragma", "no-cache")
            .putHeader("Vary", "Cookie")
            .putHeader("X-Robots-Tag", "noindex")
            .end(if (method == HttpMethod.HEAD) "" else html)
    }

    private fun redirectToLoginLocation(context: RoutingContext) {
        val response = context.response()

        if (response.ended() || response.headWritten()) {
            return
        }

        val target = maintenanceModeManager.loginLocations().firstOrNull() ?: DEFAULT_LOGIN_PATH

        response
            .setStatusCode(302)
            .putHeader("Location", target)
            .putHeader("Cache-Control", "no-store")
            .putHeader("Vary", "Cookie")
            .end()
    }

    private fun endJson503(context: RoutingContext) {
        val response = context.response()

        if (response.ended() || response.headWritten()) {
            return
        }

        val error = MaintenanceModeEnabled()

        response.statusCode = error.getStatusCode()
        response.statusMessage = error.getStatusMessage()

        response
            .putHeader("Content-Type", "application/json; charset=utf-8")
            .putHeader("Retry-After", MaintenanceModeManager.RETRY_AFTER_SECONDS.toString())
            .putHeader("Cache-Control", "no-store")
            .end(error.encode())
    }

    private fun endPlain503(context: RoutingContext) {
        val response = context.response()

        if (response.ended() || response.headWritten()) {
            return
        }

        response
            .setStatusCode(503)
            .putHeader("Content-Type", "text/plain; charset=utf-8")
            .putHeader("Retry-After", MaintenanceModeManager.RETRY_AFTER_SECONDS.toString())
            .putHeader("Cache-Control", "no-store")
            .end(PLAIN_503_BODY)
    }

    private fun endRobotsTxt(context: RoutingContext) {
        val response = context.response()

        if (response.ended() || response.headWritten()) {
            return
        }

        response
            .setStatusCode(503)
            .putHeader("Content-Type", "text/plain; charset=utf-8")
            .putHeader("Retry-After", MaintenanceModeManager.RETRY_AFTER_SECONDS.toString())
            .putHeader("Cache-Control", "no-store")
            .end(MaintenanceModeManager.ROBOTS_TXT_BODY)
    }

    private fun isSafeMethod(method: HttpMethod) = method == HttpMethod.GET || method == HttpMethod.HEAD

    companion object {
        private const val ROBOTS_TXT_PATH = "/robots.txt"
        private const val DEFAULT_LOGIN_PATH = "/login"
        private const val PLAIN_503_BODY = "The site is temporarily unavailable while we carry out maintenance."
    }
}
