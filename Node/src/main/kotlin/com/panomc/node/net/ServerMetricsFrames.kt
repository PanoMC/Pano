package com.panomc.node.net

import com.panomc.node.server.ServerRuntime
import io.vertx.core.json.JsonObject

/**
 * The two shapes a `SERVER_PROCESS_METRICS` frame comes in (§2.4.18 A).
 *
 * Built here rather than inline so the difference between them is one readable thing: what Pano
 * does with a frame depends entirely on which keys are in it, and a key that should not be there
 * is a stopped server pretending to be running.
 */
object ServerMetricsFrames {
    /**
     * A running server's frame: the process's own numbers, plus the size of its directory when a
     * walk has finished at least once.
     *
     * [ServerRuntime.Sample.cpuPercent] and [ServerRuntime.Sample.residentBytes] go out twice,
     * under the old names and the §2.4.17 ones, so a Pano that reads either sees them. They are
     * one field each, not two measurements.
     */
    fun process(
        serverUuid: String,
        t: Long,
        sample: ServerRuntime.Sample,
        diskBytes: Long?,
        diskTotalBytes: Long?,
        netRxBps: Long? = null,
        netTxBps: Long? = null
    ): JsonObject = JsonObject()
        .put("serverUuid", serverUuid)
        .put("t", t)
        .put("cpu", sample.cpuPercent)
        .put("memRss", sample.residentBytes)
        .put("cpuPercent", sample.cpuPercent)
        .put("rssBytes", sample.residentBytes)
        // Whatever the last completed walk of this server's directory found, which is null until
        // the first one lands. Never measured here and now: a forty gigabyte world would hold up
        // every other server's frame behind it.
        .put("diskBytes", diskBytes)
        // The partition the directory is on, which is what turns the number above into a reading:
        // the panel draws the pair as "41 GB of 500 GB". Cheap enough to send every tick.
        .put("diskTotalBytes", diskTotalBytes)
        // The server's own traffic in bytes per second (§2.4.22 A): only a container has a network
        // stack of its own to count, so a plain process sends null and Pano falls back to the
        // host's figure. Only ever in this frame — a stopped server has no traffic to report.
        .put("netRxBps", netRxBps)
        .put("netTxBps", netTxBps)

    /**
     * A stopped server's frame: the directory and nothing else.
     *
     * The files are still there when the process is not, and a card that shows a dash for disk
     * on every server that happens to be off would be wrong rather than unknown. The size of the
     * directory and the size of its partition and nothing else, because Pano tells this frame
     * apart from a running server's by what it does *not* carry — anything resembling a process
     * reading here and a stopped server would be recorded as a live one, with a flat line in its
     * history to prove it.
     */
    fun diskOnly(serverUuid: String, t: Long, diskBytes: Long, diskTotalBytes: Long?): JsonObject = JsonObject()
        .put("serverUuid", serverUuid)
        .put("t", t)
        .put("diskBytes", diskBytes)
        .put("diskTotalBytes", diskTotalBytes)
}
