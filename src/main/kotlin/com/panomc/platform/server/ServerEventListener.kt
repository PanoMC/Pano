package com.panomc.platform.server

import com.panomc.platform.db.model.Server
import io.vertx.core.json.JsonObject

interface ServerEventListener {
    val serverEvent: ServerEvent

    suspend fun handle(body: JsonObject, server: Server)
}