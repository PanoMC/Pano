package com.panomc.platform.node

import com.panomc.platform.util.TextUtil.convertToSnakeCase
import io.vertx.core.json.JsonObject

/**
 * Anything Pano pushes down a node socket.
 *
 * The event name is derived from the class name (`InstallServerMessage` -> `INSTALL_SERVER`), so
 * renaming a class renames a wire message: the daemon side has to move in lockstep. Deliberately
 * a separate hierarchy from the Minecraft plugin's `PlatformMessage` even though the encoding is
 * identical, so a message can never be pushed down the wrong kind of socket by accident.
 */
interface NodeMessage {
    fun getResponseName() = this.javaClass.simpleName.replace("Message", "").convertToSnakeCase().uppercase()

    fun encode(): String {
        val response = mutableMapOf<String, Any?>(
            "event" to getResponseName()
        )

        response.putAll(JsonObject.mapFrom(this).map)

        return JsonObject(response).encode()
    }
}
