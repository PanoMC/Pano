package com.panomc.platform.mail

import io.vertx.core.json.JsonObject

interface Mail {
    val templatePath: String

    val subject: String

    suspend fun generateParameters(uiAddress: String): JsonObject
}