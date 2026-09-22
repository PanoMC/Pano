package com.panomc.platform.db.model

import com.panomc.platform.db.DBEntity

/**
 * One minute of a server's performance history.
 *
 * Written by `ServerMetricsRecorder` from the latest in-memory sample, one row per online server
 * per minute, and pruned after 30 days. [tps] is the 1 minute load average, the only one of the
 * three the chart needs at this resolution. [tps], [mspt] and [cpu] are null when the server could
 * not measure them, which is normal for proxies.
 *
 * The same class carries an aggregated bucket out of `ServerMetricDao.getSeries`: there [id] is
 * -1 and [ts] is the start of the bucket.
 */
data class ServerMetric(
    val id: Long = -1,
    val serverId: Long = -1,
    val ts: Long = 0,
    val tps: Double? = null,
    val mspt: Double? = null,
    val memUsed: Long = 0,
    val memMax: Long = 0,
    val cpu: Double? = null,
    val players: Long = 0,
    /**
     * Who measured this minute: `plugin` from inside the game, `node` from the process and a
     * server list ping (§2.4.17 A).
     *
     * On the row rather than derived, because a server can change hands over the life of a chart:
     * a plugin installed halfway through a week turns node rows into plugin rows from that point
     * on, and a line that silently changed what it was measuring would be a lie drawn straight.
     * On an aggregated bucket it is the source of the rows in it, or `mixed` when it is both.
     */
    val source: String = SOURCE_PLUGIN,
    /**
     * Bytes the server's directory took at this minute, or null when nobody measured it
     * (§2.4.18 A).
     *
     * Nullable rather than 0, and kept as the bucket's maximum rather than its average, because
     * "not measured" and "empty" are different facts and a bucket where only one minute carries
     * the walk's result would otherwise average the size down towards zero.
     */
    val diskUsed: Long? = null,
    /**
     * Traffic in bytes per second at this minute, received and sent, or null when nothing measured
     * it (§2.4.22 A). The server's own for a container, its node's for a plain process — the
     * sample says which, the history keeps the figure.
     */
    val netRx: Long? = null,
    val netTx: Long? = null
) : DBEntity() {
    companion object {
        const val SOURCE_PLUGIN = "plugin"
        const val SOURCE_NODE = "node"

        /** A bucket whose rows did not all come from the same place. */
        const val SOURCE_MIXED = "mixed"
    }
}
