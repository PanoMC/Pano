package com.panomc.platform.model

import io.vertx.core.Handler
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.RoutingContext

abstract class Api : Route() {
    fun sendResult(result: Result, context: RoutingContext) {
        val response = context.response()

        response
            .putHeader("content-type", "application/json; charset=utf-8")

        when (result) {
            is Successful -> {
                val responseMap = mutableMapOf<String, Any?>(
                    "result" to "ok"
                )

                responseMap.putAll(result.map)

                response.end(
                    JsonObject(
                        responseMap
                    ).encode()
                )
            }
            is Error -> response.end(
                JsonObject(
                    mapOf(
                        "result" to "error",
                        "error" to result.errorCode
                    )
                ).encode()
            )
            is Errors -> response.end(
                JsonObject(
                    mapOf(
                        "result" to "error",
                        "error" to result.errors
                    )
                ).encode()
            )
        }
    }

    override fun getHandler() = Handler<RoutingContext> { context ->
        handler(context)
    }

    fun handler(context: RoutingContext) {
        handler(context) { result ->
            sendResult(result, context)
        }
    }

    abstract fun handler(context: RoutingContext, handler: (result: Result) -> Unit)
}