package com.panomc.platform.db.model

import com.panomc.platform.db.DBEntity

/**
 * One local day of a server's player history, kept for the Server Activity Year view (§2.4.24).
 *
 * `server_metric` keeps thirty days of minutes, which is all a Week or Month chart needs and far too
 * little for a year. This is the rollup that outlives it: one row per server per local calendar day,
 * [day] being that day's local midnight in epoch ms, [peakPlayers] the day's highest minute,
 * [avgPlayers] the mean over the [samples] minutes that were recorded. Kept for 400 days.
 */
data class ServerMetricDaily(
    val id: Long = -1,
    val serverId: Long = -1,
    val day: Long = 0,
    val peakPlayers: Long = 0,
    val avgPlayers: Double = 0.0,
    val samples: Long = 0
) : DBEntity()
