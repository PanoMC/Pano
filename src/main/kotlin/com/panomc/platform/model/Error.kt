package com.panomc.platform.model

import com.panomc.platform.ErrorCode

open class Error(open val errorCode: ErrorCode) : Throwable(errorCode.toString()), Result {
    override fun encode(): String {
        return encode(
            mapOf(
                "result" to "error",
                "error" to errorCode
            )
        )
    }
}