package com.panomc.platform.model

import com.panomc.platform.model.Result.Companion.encode

open class Successful(val responseMap: Map<String, Any?> = mapOf()) : Result {
    override fun encode(extras: Map<String, Any?>): String {
        val response = mutableMapOf<String, Any?>()

        response.putAll(responseMap)
        response.putAll(extras)

        return response.encode()
    }

    override fun getStatusCode() = 200

    override fun getStatusMessage() = ""
}