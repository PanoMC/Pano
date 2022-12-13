package com.panomc.platform.model

import io.vertx.core.json.JsonObject

interface Result {

    fun encode(responseMap: Map<String, Any?>): String {
        val response = mutableMapOf<String, Any?>(
            "result" to "ok"
        )

        response.putAll(responseMap)

        return JsonObject(response).encode()
    }

    fun encode(): String
}