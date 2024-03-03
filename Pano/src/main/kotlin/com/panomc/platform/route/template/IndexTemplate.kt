package com.panomc.platform.route.template

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.model.Path
import com.panomc.platform.model.RouteType
import com.panomc.platform.model.Template
import io.vertx.core.Handler
import io.vertx.ext.web.RoutingContext

@Endpoint
class IndexTemplate : Template() {
    private val mHotLinks = mapOf<String, String>()

    override val paths = listOf(Path("/*", RouteType.ROUTE))

    override val order = 999

    override fun getHandler() = Handler<RoutingContext> { context ->
        val response = context.response()
        val normalisedPath = context.normalizedPath()

        if (!mHotLinks[normalisedPath.lowercase()].isNullOrEmpty()) {
            response.putHeader(
                "location",
                mHotLinks[normalisedPath.lowercase()]
            ).setStatusCode(302).end()

            return@Handler
        }

        response.setStatusCode(401).end()
    }
}