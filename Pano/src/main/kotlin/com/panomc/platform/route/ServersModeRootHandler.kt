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
            context.next()

            return@Handler
        }

        val response = context.response()

        if (response.ended() || response.headWritten()) {
            return@Handler
        }

        logger.debug("Usage mode is SERVERS and no theme is bound: {} -> {}", context.normalizedPath(), PANEL_PATH)

        response
            .setStatusCode(302)
            .putHeader("Location", PANEL_PATH)
            .putHeader("Cache-Control", "no-store")
            .end()
    }

    companion object {
        private const val PANEL_PATH = "/panel"

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

            if (path.startsWith("/api")) {
                return false
            }

            return RequestClassification.isDocumentRequest(method, secFetchDest, accept)
        }
    }
}
