package com.panomc.platform.server.feature

import io.vertx.core.json.JsonObject

/**
 * What a given server can actually do right now, and who would do it (SM-52, §2.4.17).
 *
 * The JSON of this object is the contract the panel is written against: every `source` is
 * `"node" | "plugin" | "pano" | null`, and `null` is what disables a control with a reason instead
 * of hiding the page. Field order and naming follow §2.4.17 exactly — this is a wire shape, not an
 * internal convenience.
 *
 * [reasons] is the other half of every `null` (SM-70, §2.4.35): `{ "<entry id>": "<reason>" }` for
 * each entry nothing serves, keyed by the same dotted path the panel reads the entry with, so the
 * page can say what the server lacks and what would fix it instead of just greying a control out.
 */
data class ServerFeatures(
    val console: Console,
    val power: Power,
    val metrics: Metrics,
    val players: Players,
    val plugins: Plugins,
    val files: Files,
    val backups: Backups,
    val schedules: Schedules,
    val reasons: Map<String, ServerFeatureUnavailability> = emptyMap()
) {
    data class Console(
        val stream: ServerFeatureSource?,
        val history: ServerFeatureSource?,
        val input: ServerFeatureSource?
    )

    /** [start] and [kill] are node-only, so they are plain booleans rather than sources. */
    data class Power(
        val start: Boolean,
        val stop: ServerFeatureSource?,
        val restart: ServerFeatureSource?,
        val kill: Boolean
    )

    /** [host] is the node's own CPU/RAM, which belongs to the machine rather than to a process. */
    data class Metrics(
        val tps: ServerFeatureSource?,
        val memory: ServerFeatureSource?,
        val players: ServerFeatureSource?,
        val host: Boolean
    )

    /**
     * [listQuality] is `"full"` for the plugin's roster and `"sample"` for the node's server list
     * ping, whose count is exact but whose names stop at twelve.
     */
    data class Players(
        val list: ServerFeatureSource?,
        val listQuality: String?,
        val actions: ServerFeatureSource?
    ) {
        companion object {
            const val QUALITY_FULL = "full"
            const val QUALITY_SAMPLE = "sample"
        }
    }

    data class Plugins(
        val list: ServerFeatureSource?,
        val toggle: ServerFeatureSource?,
        val install: ServerFeatureSource?,
        val identify: ServerFeatureSource?
    )

    data class Files(
        val source: ServerFeatureSource?,
        val transfer: ServerFeatureSource?
    )

    /**
     * [restoreMode] is `"live"` when the node puts the files back with the server stopped, and
     * `"next-start"` when the plugin writes a marker the server applies as it boots.
     */
    data class Backups(
        val create: ServerFeatureSource?,
        val restore: ServerFeatureSource?,
        val restoreMode: String?
    ) {
        companion object {
            const val MODE_LIVE = "live"
            const val MODE_NEXT_START = "next-start"
        }
    }

    data class Schedules(val runner: ServerFeatureSource?)

    /** The source behind one pickable feature, or `null` when nothing can do it. */
    fun sourceOf(feature: ServerFeature): ServerFeatureSource? = when (feature) {
        ServerFeature.CONSOLE_STREAM -> console.stream
        ServerFeature.CONSOLE_HISTORY -> console.history
        ServerFeature.CONSOLE_INPUT -> console.input
        ServerFeature.POWER_STOP -> power.stop
        ServerFeature.POWER_RESTART -> power.restart
        ServerFeature.METRICS_TPS -> metrics.tps
        ServerFeature.METRICS_MEMORY -> metrics.memory
        ServerFeature.METRICS_PLAYERS -> metrics.players
        ServerFeature.PLAYERS_LIST -> players.list
        ServerFeature.PLAYERS_ACTIONS -> players.actions
        ServerFeature.PLUGINS_LIST -> plugins.list
        ServerFeature.PLUGINS_TOGGLE -> plugins.toggle
        ServerFeature.PLUGINS_INSTALL -> plugins.install
        ServerFeature.PLUGINS_IDENTIFY -> plugins.identify
        ServerFeature.FILES_SOURCE -> files.source
        ServerFeature.FILES_TRANSFER -> files.transfer
        ServerFeature.BACKUPS_CREATE -> backups.create
        ServerFeature.BACKUPS_RESTORE -> backups.restore
        ServerFeature.SCHEDULES_RUNNER -> schedules.runner
    }

    /**
     * Whether the entry at [id] is served — a source that is not `null`, or a node-only boolean that
     * is `true`. An id this shape does not have is never available.
     */
    fun isAvailable(id: String): Boolean = when (id) {
        POWER_START -> power.start
        POWER_KILL -> power.kill
        METRICS_HOST -> metrics.host
        else -> ServerFeature.entries.firstOrNull { it.id == id }?.let { sourceOf(it) != null } ?: false
    }

    fun toJsonObject(): JsonObject = JsonObject()
        .put(
            "console",
            JsonObject()
                .put("stream", console.stream?.id)
                .put("history", console.history?.id)
                .put("input", console.input?.id)
        )
        .put(
            "power",
            JsonObject()
                .put("start", power.start)
                .put("stop", power.stop?.id)
                .put("restart", power.restart?.id)
                .put("kill", power.kill)
        )
        .put(
            "metrics",
            JsonObject()
                .put("tps", metrics.tps?.id)
                .put("memory", metrics.memory?.id)
                .put("players", metrics.players?.id)
                .put("host", metrics.host)
        )
        .put(
            "players",
            JsonObject()
                .put("list", players.list?.id)
                .put("listQuality", players.listQuality)
                .put("actions", players.actions?.id)
        )
        .put(
            "plugins",
            JsonObject()
                .put("list", plugins.list?.id)
                .put("toggle", plugins.toggle?.id)
                .put("install", plugins.install?.id)
                .put("identify", plugins.identify?.id)
        )
        .put(
            "files",
            JsonObject()
                .put("source", files.source?.id)
                .put("transfer", files.transfer?.id)
        )
        .put(
            "backups",
            JsonObject()
                .put("create", backups.create?.id)
                .put("restore", backups.restore?.id)
                .put("restoreMode", backups.restoreMode)
        )
        .put("schedules", JsonObject().put("runner", schedules.runner?.id))
        .put(
            "reasons",
            JsonObject().also { json -> reasons.forEach { (id, why) -> json.put(id, why.reason.name) } }
        )

    companion object {
        // The three boolean entries. They are not [ServerFeature]s -- nothing can be picked for
        // them -- but "why is Start greyed out" deserves an answer just as much.
        const val POWER_START = "power.start"
        const val POWER_KILL = "power.kill"
        const val METRICS_HOST = "metrics.host"

        /** Every entry a reason can be given for, in the order the JSON lists them. */
        val ENTRY_IDS: List<String> = listOf(
            ServerFeature.CONSOLE_STREAM.id,
            ServerFeature.CONSOLE_HISTORY.id,
            ServerFeature.CONSOLE_INPUT.id,
            POWER_START,
            ServerFeature.POWER_STOP.id,
            ServerFeature.POWER_RESTART.id,
            POWER_KILL,
            ServerFeature.METRICS_TPS.id,
            ServerFeature.METRICS_MEMORY.id,
            ServerFeature.METRICS_PLAYERS.id,
            METRICS_HOST,
            ServerFeature.PLAYERS_LIST.id,
            ServerFeature.PLAYERS_ACTIONS.id,
            ServerFeature.PLUGINS_LIST.id,
            ServerFeature.PLUGINS_TOGGLE.id,
            ServerFeature.PLUGINS_INSTALL.id,
            ServerFeature.PLUGINS_IDENTIFY.id,
            ServerFeature.FILES_SOURCE.id,
            ServerFeature.FILES_TRANSFER.id,
            ServerFeature.BACKUPS_CREATE.id,
            ServerFeature.BACKUPS_RESTORE.id,
            ServerFeature.SCHEDULES_RUNNER.id
        )
    }
}
