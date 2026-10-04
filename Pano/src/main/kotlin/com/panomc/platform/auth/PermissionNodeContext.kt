package com.panomc.platform.auth

import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject

/**
 * What a permission node's `context` is allowed to contain when it arrives from the panel.
 *
 * The per-server picker writes `{ "server": [1, 2] }` here, and [PermissionServerScope] is what
 * reads it back. That reader is deliberately lenient — anything it cannot parse matches nothing,
 * so a broken context can only ever narrow access — but a permission grid that silently means
 * nothing is a bad way to find out a payload was wrong, so the write side is strict instead.
 *
 * Keys other than `server` are passed through untouched: plugins put their own scoping in here
 * and Pano has no business deciding what theirs may say.
 */
object PermissionNodeContext {
    private const val SERVER_KEY = "server"

    /** No realistic grant names more servers than this, and an unbounded list is a free write. */
    const val MAX_SERVER_IDS = 100

    /**
     * Returns the context to store, or null when the payload is not one Pano will accept.
     *
     * Server ids are normalised to numbers on the way in, so what comes back out of the database
     * is the shape the panel sent regardless of whether it sent `1` or `"1"`.
     */
    fun sanitize(value: Any?): JsonObject? {
        if (value == null) {
            return JsonObject()
        }

        if (value !is JsonObject) {
            return null
        }

        if (!value.containsKey(SERVER_KEY)) {
            return value.copy()
        }

        val serverIds = serverIds(value.getValue(SERVER_KEY)) ?: return null

        return value.copy().put(SERVER_KEY, JsonArray(serverIds))
    }

    // Accepts an array of positive whole numbers or numeric strings, and a single one of those.
    private fun serverIds(value: Any?): List<Long>? {
        val elements = when (value) {
            is JsonArray -> value.toList()
            else -> listOf(value)
        }

        if (elements.size > MAX_SERVER_IDS) {
            return null
        }

        val serverIds = mutableListOf<Long>()

        for (element in elements) {
            val serverId = serverId(element) ?: return null

            if (serverId !in serverIds) {
                serverIds.add(serverId)
            }
        }

        return serverIds
    }

    private fun serverId(value: Any?): Long? {
        val serverId = when (value) {
            is Number -> value.toLong().takeIf { value.toDouble() == it.toDouble() }
            is String -> value.trim().toLongOrNull()
            else -> null
        } ?: return null

        return serverId.takeIf { it > 0 }
    }
}
