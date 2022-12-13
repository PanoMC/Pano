package com.panomc.platform.model

open class Successful(val responseMap: Map<String, Any?> = mapOf()) : Result {
    override fun encode(): String {
        return encode(responseMap)
    }
}