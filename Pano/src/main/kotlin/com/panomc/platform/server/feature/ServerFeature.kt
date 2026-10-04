package com.panomc.platform.server.feature

/**
 * The source-valued entries of the `features` object, named the way the panel reads them.
 *
 * Only the fields whose value is a source are here: `power.start`, `power.kill` and `metrics.host`
 * are plain booleans and `players.listQuality` / `backups.restoreMode` describe a source rather
 * than being one, so none of them can be the subject of a [ServerFeatureResolver.pick].
 *
 * The [id] is what travels in the 409 body (`FEATURE_UNAVAILABLE { feature }`), so it is the dotted
 * path into the JSON shape and nothing else — the panel looks the reason up by exactly that key.
 */
enum class ServerFeature(val id: String) {
    CONSOLE_STREAM("console.stream"),
    CONSOLE_HISTORY("console.history"),
    CONSOLE_INPUT("console.input"),
    POWER_STOP("power.stop"),
    POWER_RESTART("power.restart"),
    METRICS_TPS("metrics.tps"),
    METRICS_MEMORY("metrics.memory"),
    METRICS_PLAYERS("metrics.players"),
    PLAYERS_LIST("players.list"),
    PLAYERS_ACTIONS("players.actions"),
    PLUGINS_LIST("plugins.list"),
    PLUGINS_TOGGLE("plugins.toggle"),
    PLUGINS_INSTALL("plugins.install"),
    PLUGINS_IDENTIFY("plugins.identify"),
    FILES_SOURCE("files.source"),
    FILES_TRANSFER("files.transfer"),
    BACKUPS_CREATE("backups.create"),
    BACKUPS_RESTORE("backups.restore"),
    SCHEDULES_RUNNER("schedules.runner")
}
