package com.panomc.platform.server.backup

import com.panomc.platform.error.InvalidData
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject

/**
 * How a backup is stored (backups v2).
 *
 * [FULL] is one self-contained zip per backup, which is what every backup was before v2 and what a
 * node or plugin too old to have heard of modes still makes whatever it is asked for. [SNAPSHOT]
 * is incremental and deduplicated: one repository per server, each snapshot a complete restorable
 * view that only writes the chunks the repository does not already hold.
 */
enum class BackupMode {
    FULL,
    SNAPSHOT;

    companion object {
        /** Null for anything that is not a mode, so a caller decides between refusing and defaulting. */
        fun fromId(id: String?): BackupMode? = entries.firstOrNull { it.name == id?.trim()?.uppercase() }
    }
}

/**
 * What part of the server directory a backup covers.
 *
 * [WORLDS] is resolved on the side that owns the disk — `level-name` and its nether and end, plus
 * every other top-level directory holding a `level.dat` — because only that side can see what is
 * actually there when the backup runs. [CUSTOM] is an explicit `include` list.
 */
enum class BackupScope {
    ALL,
    WORLDS,
    CUSTOM;

    companion object {
        fun fromId(id: String?): BackupScope? = entries.firstOrNull { it.name == id?.trim()?.uppercase() }
    }
}

/**
 * The options one backup is taken with, after they have been checked.
 *
 * Shared by the create endpoint and the schedule BACKUP step because both take the same five
 * fields, and a rule enforced in one of them is a rule missing from the other. [exclude] is what
 * the operator typed — the extras — and [excludeDefaults] says whether the defaults go in front of
 * them; [effectiveExclude] is what the node or plugin is actually sent, which since v2 is always
 * the full list rather than "extras the other side merges with its own defaults".
 */
data class BackupOptions(
    val mode: BackupMode = BackupMode.FULL,
    val scope: BackupScope = BackupScope.ALL,
    /** Roots of a [BackupScope.CUSTOM] backup; always empty for the other scopes. */
    val include: List<String> = emptyList(),
    val exclude: List<String> = emptyList(),
    val excludeDefaults: Boolean = true
) {
    /**
     * The exclude list the source is sent: defaults first (unless turned off), then the extras,
     * without duplicates.
     *
     * An empty result — defaults off and no extras — goes out as an empty list, and an empty list
     * is how the wire has always said "apply your own defaults". There is no way to ask the other
     * side for no excludes at all, and that is deliberate: `logs/` and `cache/` are never what
     * anybody wanted restored.
     */
    fun effectiveExclude(): List<String> =
        ((if (excludeDefaults) DEFAULT_EXCLUDE else emptyList()) + exclude).distinct()

    /** The shape a schedule step stores: what the operator sent, never the expanded list. */
    fun toPayload(): JsonObject = JsonObject()
        .put("mode", mode.name)
        .put("scope", scope.name)
        .put("include", JsonArray(include))
        .put("exclude", JsonArray(exclude))
        .put("excludeDefaults", excludeDefaults)

    companion object {
        /** Mirrors the node's and the plugin's own defaults, minus what each adds for itself. */
        val DEFAULT_EXCLUDE = listOf("logs/", "cache/", "*.jar.tmp")

        /** Most entries one include or exclude list may carry. */
        const val MAX_ENTRIES = 50

        /** Longest single include or exclude entry. */
        const val MAX_ENTRY_LENGTH = 255

        private val DRIVE_LETTER = Regex("^[A-Za-z]:")

        /**
         * Reads the options out of [json] (a create body or a schedule step's payload), or throws
         * [InvalidData] naming the field that was wrong.
         *
         * Everything is optional and absent means today's behaviour: a full zip of the whole
         * directory with the default excludes. An unknown mode or scope is refused rather than
         * defaulted, because quietly taking a full backup when somebody asked for something else
         * is the kind of surprise that is only discovered on the day it matters.
         */
        fun parse(json: JsonObject?): BackupOptions {
            val body = json ?: JsonObject()

            val mode = body.getValue("mode")?.let {
                BackupMode.fromId(it as? String) ?: throw InvalidData(extras = mapOf("field" to "mode"))
            } ?: BackupMode.FULL

            val scope = body.getValue("scope")?.let {
                BackupScope.fromId(it as? String) ?: throw InvalidData(extras = mapOf("field" to "scope"))
            } ?: BackupScope.ALL

            val include = readList(body, "include")
            val exclude = readList(body, "exclude")

            if (scope == BackupScope.CUSTOM && include.isEmpty()) {
                throw InvalidData(extras = mapOf("field" to "include"))
            }

            val excludeDefaults = when (val value = body.getValue("excludeDefaults")) {
                null -> true
                is Boolean -> value
                else -> throw InvalidData(extras = mapOf("field" to "excludeDefaults"))
            }

            return BackupOptions(
                mode = mode,
                scope = scope,
                // An include list only means something for CUSTOM; keeping one for the other scopes
                // would store a list that looks like it did something.
                include = if (scope == BackupScope.CUSTOM) include else emptyList(),
                exclude = exclude,
                excludeDefaults = excludeDefaults
            )
        }

        private fun readList(body: JsonObject, field: String): List<String> {
            val raw = body.getValue(field) ?: return emptyList()

            val array = raw as? JsonArray ?: throw InvalidData(extras = mapOf("field" to field))

            if (array.size() > MAX_ENTRIES) {
                throw InvalidData(extras = mapOf("field" to field))
            }

            return array.map { entry ->
                val text = (entry as? String)?.trim() ?: throw InvalidData(extras = mapOf("field" to field))

                if (!isSafeEntry(text)) {
                    throw InvalidData(extras = mapOf("field" to field, "value" to text.take(MAX_ENTRY_LENGTH)))
                }

                text
            }.distinct()
        }

        /**
         * Whether [entry] is a server-relative path or pattern the other side can be trusted with.
         *
         * The node and the plugin both check again — they own the disk — so this is not the
         * boundary, only the place an obviously wrong entry is refused in front of the person who
         * typed it: nothing empty or overlong, no NUL, no backslash, nothing absolute, no drive
         * letter and no `..` segment. A trailing `/` (directory prefix) and `*` (file-name glob)
         * are the matcher's own syntax and stay.
         */
        fun isSafeEntry(entry: String): Boolean {
            if (entry.isEmpty() || entry.length > MAX_ENTRY_LENGTH) {
                return false
            }

            if (entry.contains('\u0000') || entry.contains('\\')) {
                return false
            }

            if (entry.startsWith("/") || DRIVE_LETTER.containsMatchIn(entry)) {
                return false
            }

            val segments = entry.split('/').filter { it.isNotEmpty() }

            if (segments.isEmpty() || segments.any { it == ".." || it == "." }) {
                return false
            }

            return true
        }
    }
}
