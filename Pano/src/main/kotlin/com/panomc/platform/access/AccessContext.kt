package com.panomc.platform.access

import io.vertx.ext.web.RoutingContext

/** How the `Origin` of a request relates to this site (doc 05 §5). Filled by the origin policy; [NONE] until then. */
enum class OriginClass { NONE, SAME, ALLOWED, FOREIGN }

/**
 * What the access plane decided about a request, once, before anything else looks at it (doc 05 §3.2):
 * the matched front-end [key] (`null` = an ordinary request), the [clientIp] every limiter, session
 * and log must use, and the [origin] class.
 *
 * [clientIpFromKey] says [clientIp] is the `X-Pano-Client-Ip` value a valid key declared (and not the
 * address Pano worked out itself), which decides the rate-limit bucket (doc 05 §7).
 */
data class AccessContext(
    val key: FrontendKeyRef?,
    val clientIp: String,
    val origin: OriginClass = OriginClass.NONE,
    val clientIpFromKey: Boolean = false
) {
    companion object {
        /** The routing-context key the access handler stores the context under. */
        const val CONTEXT_KEY = "pano.access"

        /** The context [AccessPlaneHandler] stored on [context], or `null` when it did not run. */
        fun of(context: RoutingContext): AccessContext? = context.get<AccessContext>(CONTEXT_KEY)
    }
}
