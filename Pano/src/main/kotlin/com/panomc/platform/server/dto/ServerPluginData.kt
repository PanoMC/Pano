package com.panomc.platform.server.dto

import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject

/**
 * One plugin or mod installed on a connected server, as the plugin reports it.
 *
 * `enabled` is only meaningful on the Bukkit family, where a plugin can be turned on and off at
 * runtime; everywhere else the list is read-only and everything reports as enabled.
 */
data class ServerPluginData(
    val name: String = "",
    val version: String? = null,
    val authors: List<String>? = null,
    val description: String? = null,
    val enabled: Boolean = true,
    val file: String? = null
) {
    fun toJsonObject(): JsonObject = JsonObject()
        .put("name", name)
        .put("version", version)
        .put("authors", JsonArray(authors ?: emptyList<String>()))
        .put("description", description)
        .put("enabled", enabled)
        .put("file", file)
}
