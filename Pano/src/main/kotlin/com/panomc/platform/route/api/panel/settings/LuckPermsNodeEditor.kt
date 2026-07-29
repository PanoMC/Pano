package com.panomc.platform.route.api.panel.settings

import io.vertx.core.json.JsonObject

/**
 * Applies the permission-node changes an admin made on the LuckPerms migration review screen to the
 * data read back from the source database.
 *
 * The review and the import are two separate requests, and the import re-reads the source rather
 * than trusting the browser with the whole data set. Edits therefore identify a node by its content
 * — permission, server, world, expiry and contexts — instead of by position, because the source
 * tables are read without an ORDER BY and row order is not guaranteed to be the same twice.
 */
object LuckPermsNodeEditor {
    // A separator that cannot occur inside any of the joined values.
    private const val KEY_SEPARATOR = "\u0000"

    /**
     * Apply [edits] to [data] in place.
     *
     * Edits are objects of the shape:
     * `{ holderType: "GROUP"|"USER", holderKey: String, action: "add"|"update"|"remove",
     *    match: { permission, server, world, expiry, contexts }, node: { ...same fields, value } }`
     *
     * `match` identifies the existing node for `update` and `remove`; `node` carries the new values
     * for `add` and `update`. Anything malformed is ignored rather than failing the whole import.
     */
    fun apply(data: PanelLuckPermsMigrationUploadAPI.LuckPermsData, edits: List<JsonObject>) {
        if (edits.isEmpty()) {
            return
        }

        applyTo(
            data.groupPermissions,
            edits.filter { it.getString("holderType")?.uppercase() == "GROUP" },
            isGroup = true
        )
        applyTo(
            data.userPermissions,
            edits.filter { it.getString("holderType")?.uppercase() == "USER" },
            isGroup = false
        )
    }

    private fun applyTo(
        target: MutableList<PanelLuckPermsMigrationUploadAPI.LPPermission>,
        edits: List<JsonObject>,
        isGroup: Boolean
    ) {
        if (edits.isEmpty()) {
            return
        }

        val removals = mutableSetOf<String>()
        val updates = mutableMapOf<String, JsonObject>()
        val additions = mutableListOf<PanelLuckPermsMigrationUploadAPI.LPPermission>()

        edits.forEach { edit ->
            val holderKey = edit.getString("holderKey")?.takeIf { it.isNotBlank() } ?: return@forEach

            when (edit.getString("action")?.lowercase()) {
                "remove" -> edit.getJsonObject("match")?.let { removals.add(matchKey(holderKey, it)) }

                "update" -> {
                    val match = edit.getJsonObject("match") ?: return@forEach
                    val node = edit.getJsonObject("node") ?: return@forEach

                    updates[matchKey(holderKey, match)] = node
                }

                "add" -> edit.getJsonObject("node")
                    ?.let { buildPermission(holderKey, it, isGroup) }
                    ?.let { additions.add(it) }
            }
        }

        if (removals.isEmpty() && updates.isEmpty() && additions.isEmpty()) {
            return
        }

        val rebuilt = mutableListOf<PanelLuckPermsMigrationUploadAPI.LPPermission>()

        target.forEach { perm ->
            val holderKey = (if (isGroup) perm.groupName else perm.uuid) ?: ""
            val key = matchKey(holderKey, perm)

            if (key in removals) {
                return@forEach
            }

            val update = updates[key]

            rebuilt.add(if (update == null) perm else mergePermission(perm, update))
        }

        rebuilt.addAll(additions)

        target.clear()
        target.addAll(rebuilt)
    }

    private fun matchKey(holderKey: String, node: JsonObject) = matchKey(
        holderKey,
        node.getString("permission") ?: "",
        node.getString("server") ?: "global",
        node.getString("world") ?: "global",
        node.getLong("expiry") ?: 0L,
        node.getString("contexts") ?: "{}"
    )

    private fun matchKey(holderKey: String, perm: PanelLuckPermsMigrationUploadAPI.LPPermission) =
        matchKey(holderKey, perm.permission, perm.server, perm.world, perm.expiry, perm.contexts)

    private fun matchKey(
        holderKey: String,
        permission: String,
        server: String,
        world: String,
        expiry: Long,
        contexts: String
    ) = listOf(holderKey, permission, server, world, expiry.toString(), contexts)
        .joinToString(KEY_SEPARATOR)

    private fun buildPermission(
        holderKey: String,
        node: JsonObject,
        isGroup: Boolean
    ): PanelLuckPermsMigrationUploadAPI.LPPermission? {
        val permission = node.getString("permission")?.trim()?.takeIf { it.isNotBlank() } ?: return null

        return PanelLuckPermsMigrationUploadAPI.LPPermission(
            groupName = if (isGroup) holderKey else null,
            uuid = if (isGroup) null else holderKey,
            permission = permission,
            value = node.getBoolean("value") ?: true,
            server = node.getString("server")?.takeIf { it.isNotBlank() } ?: "global",
            world = node.getString("world")?.takeIf { it.isNotBlank() } ?: "global",
            expiry = node.getLong("expiry") ?: 0L,
            contexts = node.getString("contexts") ?: "{}"
        )
    }

    private fun mergePermission(
        perm: PanelLuckPermsMigrationUploadAPI.LPPermission,
        node: JsonObject
    ) = perm.copy(
        permission = node.getString("permission")?.trim()?.takeIf { it.isNotBlank() } ?: perm.permission,
        value = node.getBoolean("value") ?: perm.value,
        server = node.getString("server")?.takeIf { it.isNotBlank() } ?: perm.server,
        world = node.getString("world")?.takeIf { it.isNotBlank() } ?: perm.world,
        expiry = node.getLong("expiry") ?: perm.expiry,
        contexts = node.getString("contexts") ?: perm.contexts
    )
}
