package com.panomc.platform.server.message

import com.panomc.platform.server.PlatformMessage

/**
 * Tells a connected Pano plugin to replace itself with a newer build (`PANO_PLUGIN_UPDATE`).
 *
 * The one update that cannot go through a node: a linked server has none, so the only thing on
 * that machine that can write its plugin directory is the plugin that is running out of it. It
 * downloads [url] — a path, resolved against the Pano it is connected to and fetched with its own
 * platform token — checks the bytes against [sha256] and [size], stages them under its data folder
 * and swaps its own jar when the server stops (or, on the Bukkit family, drops them into
 * `plugins/update/` and lets the server do it at the next boot).
 *
 * A push rather than a request: the download takes as long as it takes, so progress comes back as
 * ordinary `TASK_PROGRESS` frames for [taskId] and the outcome as `PANO_PLUGIN_UPDATE_RESULT`
 * carrying the same [eventId]. A plugin too old to know this message never answers, which is why
 * Pano only sends it to plugins that announce the `self-update` capability.
 */
data class PanoPluginUpdateMessage(
    val eventId: String,
    val taskId: String,
    /** `/api/server/pano-plugin/jar`; relative on purpose, only the plugin knows how it reaches Pano. */
    val url: String,
    val sha256: String,
    val size: Long,
    /** The name Pano keeps the jar under, e.g. `pano-velocity-1.0.0-alpha.63.jar`. */
    val fileName: String,
    /** The version being installed, or `local-build` for a development jar; reported back as `stagedVersion`. */
    val version: String?
) : PlatformMessage
