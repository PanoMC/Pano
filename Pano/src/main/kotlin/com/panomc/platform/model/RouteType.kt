package com.panomc.platform.model

import io.vertx.core.http.HttpMethod

enum class RouteType(val vertxHttpMethod: HttpMethod?) {
    ROUTE(null),
    GET(HttpMethod.GET),
    POST(HttpMethod.POST),
    DELETE(HttpMethod.DELETE),
    PUT(HttpMethod.PUT)
}