package com.panomc.platform.route

import com.panomc.platform.config.ConfigManager
import com.panomc.platform.setup.SetupManager
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
 * Usage-mode gate: in [UsageMode.SERVERS] the install is a game-server panel, not a website, so
 * every theme page other than the auth pages is a dead end — this handler bounces those HTML
 * *document* requests to `/panel`.
 *
 * The theme process keeps running in SERVERS mode on purpose: the panel's login, reset-password
 * and activation pages live in the theme and `UIManager.activatePanelUI` proxies unauthenticated
 * `/panel/…` requests to it. Dropping the theme has to wait for a panel-native login (U-06), so
 * until then the theme stays up and only its non-auth pages are redirected away.
 *
 * The auth pages that stay reachable are the vanilla-theme routes under `src/routes/(theme)/`:
 * `/login`, `/reset-password`, `/renew-password`, `/activate` and `/activate-new-email`. The theme
 * forks (blaze, blocky, frost, banana) share the same route set, so one list covers every theme.
 *
 * Only page traffic reaches this handler: it is an order-2 wildcard handler, and `@Endpoint`
 * handlers sit at order 1 and terminate without calling `next()`, so API routes never get here.
 * Registered as a raw router handler by [RouterProvider], never as an `@Endpoint`/`Template` bean —
 * those get a default `BodyHandler`, and `ProxyHandlerImpl` fails any proxied request with a 500
 * once a body handler has run.
 */
@Lazy
@Component
@Scope(value = ConfigurableBeanFactory.SCOPE_SINGLETON)
class UsageModeGateHandler(
    private val configManager: ConfigManager,
    private val setupManager: SetupManager,
    private val logger: Logger
) {
    fun create(): Handler<RoutingContext> = Handler { context ->
        // Fast path: a single field read, no allocation, for WEBSITE and BOTH. The config is read
        // per request so switching the mode in the panel takes effect without a restart.
        if (configManager.config.effectiveUsageMode != UsageMode.SERVERS) {
            context.next()

            return@Handler
        }

        val request = context.request()
        val path = context.normalizedPath()

        val decision = decide(
            mode = UsageMode.SERVERS,
            setupDone = setupManager.isSetupDone(),
            path = path,
            method = request.method(),
            secFetchDest = request.getHeader("Sec-Fetch-Dest"),
            accept = request.getHeader("Accept")
        )

        if (decision == Decision.PASS) {
            context.next()

            return@Handler
        }

        redirectToPanel(context, path)
    }

    private fun redirectToPanel(context: RoutingContext, path: String) {
        val response = context.response()

        if (response.ended() || response.headWritten()) {
            return
        }

        logger.debug("Usage mode is SERVERS: redirecting theme page {} -> {}", path, PANEL_PATH)

        response
            .setStatusCode(302)
            .putHeader("Location", PANEL_PATH)
            .putHeader("Cache-Control", "no-store")
            .end()
    }

    enum class Decision {
        PASS,
        REDIRECT
    }

    companion object {
        private const val PANEL_PATH = "/panel"

        /**
         * Paths that are never a theme page: the panel, the APIs, and the theme's own assets and
         * resources. The asset prefixes are belt and braces — a subresource is not a document
         * request either — but they are cheaper than the header checks that would otherwise run.
         */
        private val PASS_PREFIXES = listOf(
            "/panel",
            "/api",
            "/plugins/",
            "/_app/",
            "/lib/",
            "/runtime/",
            "/theme-api/",
            "/style.css",
            "/favicon"
        )

        /**
         * Theme routes that must keep working in SERVERS mode: they are how an admin logs into the
         * panel, recovers a password and activates an account or a new e-mail address.
         */
        private val AUTH_PATHS = listOf(
            "/login",
            "/reset-password",
            "/renew-password",
            "/activate",
            "/activate-new-email"
        )

        internal fun decide(
            mode: UsageMode,
            setupDone: Boolean,
            path: String,
            method: HttpMethod,
            secFetchDest: String?,
            accept: String?
        ): Decision {
            if (mode != UsageMode.SERVERS) return Decision.PASS

            // The setup wizard is served by setup-ui on the same paths the theme would own, and a
            // half-installed platform has no panel to redirect to.
            if (!setupDone) return Decision.PASS

            if (PASS_PREFIXES.any { path.startsWith(it) }) return Decision.PASS

            // Only navigations are redirected; XHR, fetch, images and scripts are left alone so a
            // page that is allowed to render still works.
            if (!RequestClassification.isDocumentRequest(method, secFetchDest, accept)) return Decision.PASS

            if (AUTH_PATHS.any { path == it || path.startsWith("$it/") }) return Decision.PASS

            return Decision.REDIRECT
        }
    }
}
