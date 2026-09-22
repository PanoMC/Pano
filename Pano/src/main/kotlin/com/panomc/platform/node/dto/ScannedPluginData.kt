package com.panomc.platform.node.dto

import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject

/**
 * One jar a node found in a server's plugin directory, as its own descriptor describes it
 * (§2.4.17 B).
 *
 * Deliberately not [com.panomc.platform.server.dto.ServerPluginData]: that one is what a running
 * server says it has loaded, this one is what is sitting on the disk, and the panel tells the two
 * apart because the difference is the useful part — a jar that is present but not loaded is a
 * plugin installed since the last boot.
 *
 * Everything is nullable but [file], because the descriptor formats disagree about which keys are
 * mandatory and a node newer than this Pano may learn to read one more of them.
 */
data class ScannedPluginData(
    val file: String,
    val name: String,
    val version: String? = null,
    val main: String? = null,
    /** API or loader version the descriptor declares, e.g. a Bukkit `api-version`. */
    val api: String? = null,
    val description: String? = null,
    val authors: List<String> = emptyList(),
    /** False when the file carries the `.disabled` suffix. */
    val enabled: Boolean = true,
    /** `plugin` or `mod`, decided by the descriptor and the directory it was found in. */
    val kind: String = KIND_PLUGIN
) {
    fun toJsonObject(): JsonObject = JsonObject()
        .put("file", file)
        .put("name", name)
        .put("version", version)
        .put("main", main)
        .put("api", api)
        .put("description", description)
        .put("authors", JsonArray(authors))
        .put("enabled", enabled)
        .put("kind", kind)

    companion object {
        const val KIND_PLUGIN = "plugin"
        const val KIND_MOD = "mod"

        /** Most entries Pano accepts from one scan, so a `mods/` directory cannot flood a page. */
        const val MAX_ENTRIES = 1000

        private const val MAX_FIELD_LENGTH = 128
        private const val MAX_DESCRIPTION_LENGTH = 512
        private const val MAX_AUTHORS = 32

        /**
         * Reads one entry of a `PLUGIN_SCAN` reply, or `null` when it is not usable.
         *
         * Every string is cut to a length a panel can render: the values come out of a file
         * somebody else wrote, and a `description` the size of a jar is a rendering problem
         * rather than an attack, but it is still not something to put in a JSON response.
         */
        fun fromJson(json: JsonObject): ScannedPluginData? {
            val file = json.getString("file")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val name = json.getString("name")?.trim()?.takeIf { it.isNotEmpty() }
                ?: file.removeSuffix(".disabled").removeSuffix(".jar")

            return ScannedPluginData(
                file = file.take(MAX_FIELD_LENGTH),
                name = name.take(MAX_FIELD_LENGTH),
                version = json.getString("version")?.take(MAX_FIELD_LENGTH),
                main = json.getString("main")?.take(MAX_FIELD_LENGTH),
                api = json.getString("api")?.take(MAX_FIELD_LENGTH),
                description = json.getString("description")?.take(MAX_DESCRIPTION_LENGTH),
                authors = json.getJsonArray("authors")
                    .orEmpty()
                    .mapNotNull { it as? String }
                    .take(MAX_AUTHORS)
                    .map { it.take(MAX_FIELD_LENGTH) },
                enabled = json.getBoolean("enabled", !file.endsWith(DISABLED_SUFFIX)),
                kind = if (json.getString("kind") == KIND_MOD) KIND_MOD else KIND_PLUGIN
            )
        }

        /** Every usable entry of a `PLUGIN_SCAN` reply. */
        fun listFrom(payload: JsonObject): List<ScannedPluginData> = payload.getJsonArray("plugins")
            // Iterated as a JsonArray, never through `.list`: a reply decoded off the wire keeps
            // its entries as raw maps, and only the array's own iterator wraps them as objects.
            ?.mapNotNull { it as? JsonObject }
            .orEmpty()
            .take(MAX_ENTRIES)
            .mapNotNull { fromJson(it) }

        const val DISABLED_SUFFIX = ".disabled"

        private fun JsonArray?.orEmpty(): List<Any?> = this?.list ?: emptyList()
    }
}
