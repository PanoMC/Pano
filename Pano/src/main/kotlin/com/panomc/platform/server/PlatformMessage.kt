package com.panomc.platform.server

import com.panomc.platform.util.TextUtil.convertToSnakeCase
import io.vertx.core.json.JsonObject

interface PlatformMessage {
    fun getResponseName() = this.javaClass.simpleName.convertToSnakeCase().uppercase()

    fun encode(): String {
        val response = mutableMapOf<String, Any?>(
            "event" to getResponseName()
        )

        response.putAll(JsonObject.mapFrom(this).map)

        return JsonObject(response).encode()
    }
}