package com.panomc.platform.util

import io.vertx.core.http.HttpMethod
import io.vertx.core.http.HttpServerRequest

/**
 * Request-shape checks shared by the order-2 wildcard gates
 * ([com.panomc.platform.maintenance.MaintenanceGateHandler] and
 * [com.panomc.platform.route.UsageModeGateHandler]).
 *
 * Both gates have to tell a top-level navigation apart from a subresource fetch, and both must
 * answer it the same way — a page that is redirected while its stylesheets and data calls are not
 * (or the other way round) produces a half-working site.
 */
object RequestClassification {
    fun isSafeMethod(method: HttpMethod) = method == HttpMethod.GET || method == HttpMethod.HEAD

    /**
     * A top-level navigation, i.e. the thing a reload repeats. `Sec-Fetch-Dest` is sent by every
     * current browser; the `Accept` fallback covers the rest and command-line clients.
     */
    fun isDocumentRequest(request: HttpServerRequest): Boolean = isDocumentRequest(
        request.method(),
        request.getHeader("Sec-Fetch-Dest"),
        request.getHeader("Accept")
    )

    fun isDocumentRequest(method: HttpMethod, secFetchDest: String?, accept: String?): Boolean {
        if (secFetchDest != null) {
            return secFetchDest.equals("document", ignoreCase = true) ||
                    secFetchDest.equals("iframe", ignoreCase = true)
        }

        if (!isSafeMethod(method)) {
            return false
        }

        return accept?.contains("text/html", ignoreCase = true) == true
    }
}
