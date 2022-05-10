package com.panomc.platform.model

import io.vertx.core.Handler
import io.vertx.ext.web.RoutingContext

abstract class Route {
    open val routeType = RouteType.ROUTE

    open val order = 1

    abstract val routes: ArrayList<String>

    abstract fun getHandler(): Handler<RoutingContext>

    open fun getFailureHandler(): Handler<RoutingContext> = Handler { request ->
        val response = request.response()

        if (!response.ended())
            response.end()
    }
}