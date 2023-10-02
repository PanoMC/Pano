package com.panomc.platform.model

import com.panomc.platform.ErrorCode
import com.panomc.platform.model.Result.Companion.encode

open class Error(private val errorCode: ErrorCode, private val extras: Map<String, Any?> = mapOf()) :
    Throwable(errorCode.toString()), Result {

    override fun encode(extras: Map<String, Any?>): String {
        val response = mutableMapOf<String, Any?>(
            "result" to "error",
            "error" to errorCode
        )

        response.putAll(this.extras)
        response.putAll(extras)

        return response.encode()
    }

    override fun getStatusCode(): Int = errorCode.statusCode

    override fun getStatusMessage(): String = errorCode.statusMessage
}