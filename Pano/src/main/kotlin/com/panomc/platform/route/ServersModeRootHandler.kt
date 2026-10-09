package com.panomc.platform.route

import com.panomc.platform.config.ConfigManager
import com.panomc.platform.setup.SetupManager
import com.panomc.platform.ui.FrontendMode
import com.panomc.platform.util.RequestClassification
import com.panomc.platform.util.UsageMode
import io.vertx.core.Handler
import io.vertx.core.http.HttpMethod
import io.vertx.ext.web.RoutingContext
import org.slf4j.Logger
import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.context.annotation.Lazy
import org.springframework.context.annotation.Scope
import org.springframework.stereotype.Component

/**
 * The floor of a servers-only install: any page request that nothing else claimed goes to `/panel`.
 *
 * Order 6 is where a request ends up when no UI owns the wildcard route, and in SERVERS mode that
 * is now the normal state rather than a brief window during a theme switch — there is no theme
 * process at all. Without this, `/` would answer 503 "UI unavailable" on a perfectly healthy
 * install, which is the single most alarming thing a new operator could be shown.
 *
 * It also catches the theme paths [UsageModeGateHandler] deliberately lets through: `/login`,
 * `/reset-password` and the activation pages were reachable because the theme served them and the
 * panel had no login of its own. With a panel-native login those addresses have no owner, so
 * sending them to the panel is both the only useful answer and the one an old bookmark expects.
 *
 * An address that carries a query string keeps its path and query: `/x?q` goes to `/panel/x?q`
 * (see [panelLocation]). Those are the sign-in hops that land on a site path — an OAuth callback
 * (`/social-login/callback?code=…`), a magic link, `/login?socialError=…` — and the panel serves
 * them itself (plugin pages registered `public`, its own `/panel/login`). A bare old bookmark
 * still goes to the dashboard.
 *
 * Only document requests are redirected. A stylesheet or an API call that reaches this far is a
 * genuine "nothing serves this", and turning a missing asset into a 302 to an HTML page would give
 * the browser something worse than the error.
 */
@Lazy
@Component
@Scope(value = ConfigurableBeanFactory.SCOPE_SINGLETON)
class ServersModeRootHandler(
    private val configManager: ConfigManager,
    private val setupManager: SetupManager,
    private val logger: Logger
) {
    fun create(): Handler<RoutingContext> = Handler { context ->
        val request = context.request()

        val decision = decide(
            mode = configManager.config.effectiveUsageMode,
            setupDone = setupManager.isSetupDone(),
            path = context.normalizedPath(),
            secFetchDest = request.getHeader("Sec-Fetch-Dest"),
            accept = request.getHeader("Accept"),
            method = request.method()
        )

        if (!decision) {
            val none = decideNone(
                frontendMode = configManager.config.effectiveFrontend.parsedMode,
                usageMode = configManager.config.effectiveUsageMode,
                setupDone = setupManager.isSetupDone(),
                path = context.normalizedPath(),
                method = request.method(),
                siteUrl = configManager.config.effectiveFrontend.siteUrl,
                requestHost = request.authority()?.host(),
                requestPort = request.authority()?.port() ?: -1,
                requestScheme = if (request.isSSL) "https" else "http"
            )

            when (none) {
                NoneDecision.Pass -> context.next()
                NoneDecision.NotFound -> notFound(context)
                is NoneDecision.Redirect -> redirect(context, none.location)
            }

            return@Handler
        }

        val response = context.response()

        if (response.ended() || response.headWritten()) {
            return@Handler
        }

        val location = panelLocation(context.normalizedPath(), request.query())

        logger.debug("Usage mode is SERVERS and no theme is bound: {} -> {}", context.normalizedPath(), location)

        response
            .setStatusCode(302)
            .putHeader("Location", location)
            .putHeader("Cache-Control", "no-store")
            .end()
    }

    private fun redirect(context: RoutingContext, location: String) {
        val response = context.response()

        if (response.ended() || response.headWritten()) {
            return
        }

        logger.debug("Front-end mode is NONE: {} -> {}", context.normalizedPath(), location)

        response
            .setStatusCode(302)
            .putHeader("Location", location)
            .putHeader("Cache-Control", "no-store")
            .end()
    }

    private fun notFound(context: RoutingContext) {
        val response = context.response()

        if (response.ended() || response.headWritten()) {
            return
        }

        response
            .setStatusCode(404)
            .putHeader("Cache-Control", "no-store")
            .putHeader("Content-Type", "text/plain; charset=utf-8")
            .end("Not Found")
    }

    /** What the `NONE` front-end mode makes of a request that nothing else answered. */
    internal sealed class NoneDecision {
        /** Not this rule's business (another mode, SERVERS, setup, or a panel path). */
        object Pass : NoneDecision()

        object NotFound : NoneDecision()

        class Redirect(val location: String) : NoneDecision()
    }

    companion object {
        private const val PANEL_PATH = "/panel"

        /**
         * The root rule of `frontend.mode = NONE` (doc 05 §8): no wildcard front-end exists, so `GET /` goes to
         * `site-url` when that is another host, else to `/panel`; the `/_pano` paths belong to the fallback pages
         * (they answer before this order; a path that still reaches here is unknown) and every other path is a 404.
         *
         * `/panel` is never answered here for the reason [decide] gives, and SERVERS installs and a wizard
         * that is not finished keep their own rules.
         */
        internal fun decideNone(
            frontendMode: FrontendMode,
            usageMode: UsageMode,
            setupDone: Boolean,
            path: String,
            method: HttpMethod,
            siteUrl: String,
            requestHost: String?,
            requestPort: Int,
            requestScheme: String
        ): NoneDecision {
            if (frontendMode != FrontendMode.NONE || usageMode == UsageMode.SERVERS || !setupDone) {
                return NoneDecision.Pass
            }

            if (path == PANEL_PATH || path.startsWith("$PANEL_PATH/")) {
                return NoneDecision.Pass
            }

            if ((path == "/" || path.isEmpty()) && (method == HttpMethod.GET || method == HttpMethod.HEAD)) {
                return NoneDecision.Redirect(
                    otherSite(siteUrl, requestHost, requestPort, requestScheme) ?: PANEL_PATH
                )
            }

            return NoneDecision.NotFound
        }

        /** [siteUrl] when it names another host (or port) than the request came to; null when it is this site or unusable. */
        private fun otherSite(siteUrl: String, requestHost: String?, requestPort: Int, requestScheme: String): String? {
            val text = siteUrl.trim()

            if (text.isEmpty()) return null

            val uri = try {
                java.net.URI(text)
            } catch (_: Exception) {
                return null
            }

            val scheme = uri.scheme?.lowercase()

            if ((scheme != "http" && scheme != "https") || uri.host.isNullOrEmpty()) return null

            val sitePort = if (uri.port == -1) (if (scheme == "https") 443 else 80) else uri.port
            val here = requestHost ?: return text
            val herePort = if (requestPort == -1) (if (requestScheme == "https") 443 else 80) else requestPort

            return if (uri.host.equals(here, ignoreCase = true) && sitePort == herePort) null else text
        }

        /**
         * Where a SERVERS install sends a page request for a site path.
         *
         * With a query string the address is state for one specific page — an OAuth `code` and
         * `state`, a magic-link `token`, a `?socialError=` for the login form — and dropping it
         * would break that flow, so path and query move under `/panel` as they are. Without one
         * it is an ordinary site page the panel has no counterpart for, and the dashboard is the
         * useful answer.
         */
        internal fun panelLocation(path: String, query: String?): String {
            if (query.isNullOrEmpty()) {
                return PANEL_PATH
            }

            val panelPath = if (path.isEmpty() || path == "/") PANEL_PATH else PANEL_PATH + path

            return "$panelPath?$query"
        }

        /**
         * Whether this request should be sent to the panel instead of the "no UI" error.
         *
         * `/panel` itself is never redirected. Reaching order 6 under `/panel` means the panel
         * proxy is not bound — during boot, or while panel-ui is restarting — and answering that
         * with a redirect to `/panel` would be an infinite loop instead of a retryable 503.
         */
        internal fun decide(
            mode: UsageMode,
            setupDone: Boolean,
            path: String,
            secFetchDest: String?,
            accept: String?,
            method: HttpMethod
        ): Boolean {
            if (mode != UsageMode.SERVERS) {
                return false
            }

            // A half-installed platform has no panel to send anybody to; setup-ui owns these paths.
            if (!setupDone) {
                return false
            }

            if (path == PANEL_PATH || path.startsWith("$PANEL_PATH/")) {
                return false
            }

            if (ApiPaths.isApi(path)) {
                return false
            }

            return RequestClassification.isDocumentRequest(method, secFetchDest, accept)
        }
    }
}
