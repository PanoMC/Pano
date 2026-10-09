package com.panomc.platform.route.api.widget

import com.panomc.platform.ui.WidgetRuntimeFile
import io.vertx.core.buffer.Buffer
import io.vertx.ext.web.RoutingContext

/** Shared answer writing of the widget file endpoints. */
internal object WidgetFiles {
    /** Hashed names never change; everything else is validated by the runtime hash and revalidated after 5 minutes. */
    const val IMMUTABLE = "public, max-age=31536000, immutable"
    const val FIVE_MINUTES = "public, max-age=300"

    fun send(context: RoutingContext, file: WidgetRuntimeFile, forceShortCache: Boolean = false) {
        val response = context.response()
        val etag = "\"${file.etag}\""

        response.putHeader("Content-Type", file.contentType)
        response.putHeader("X-Content-Type-Options", "nosniff")
        response.putHeader("ETag", etag)
        response.putHeader("Cache-Control", if (file.immutable && !forceShortCache) IMMUTABLE else FIVE_MINUTES)

        val ifNoneMatch = context.request().getHeader("If-None-Match")

        if (ifNoneMatch?.split(',')?.map { it.trim().removePrefix("W/") }?.contains(etag) == true) {
            response.setStatusCode(304).end()

            return
        }

        response.putHeader("Content-Length", file.bytes.size.toString())
        response.end(Buffer.buffer(file.bytes))
    }
}
