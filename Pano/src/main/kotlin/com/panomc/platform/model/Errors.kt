package com.panomc.platform.model

import com.panomc.platform.model.Result.Companion.encode

class Errors(val errors: Map<String, Any?>) : Throwable(), Result {
    override fun encode(extras: Map<String, Any?>): String {
        val response = mutableMapOf<String, Any?>(
            "result" to "errors",
            "errors" to errors
        )

        response.putAll(extras)

        return response.encode()
    }

    override fun getStatusCode() = 400

    override fun getStatusMessage() = ""
}