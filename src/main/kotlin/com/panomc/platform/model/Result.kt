package com.panomc.platform.model

import io.vertx.core.json.JsonObject

interface Result {

    fun encode(extras: Map<String, Any?> = mapOf()): String

    fun getStatusCode(): Int

    fun getStatusMessage(): String

    companion object {
        fun Map<String, Any?>.encode(): String {
            val response = mutableMapOf<String, Any?>(
                "result" to "ok"
            )

            response.putAll(this)

            return JsonObject(response).encode()

        }
    }
}