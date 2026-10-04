package com.panomc.platform.node.event.request

import com.panomc.platform.node.NodeEventRequest

/**
 * Operating-system view of one server process, reported every ten seconds while it runs.
 *
 * Since §2.4.17 B this is also the fallback for everything a server with no Pano plugin in it
 * cannot report from the inside: the node adds a Minecraft server list ping to `127.0.0.1:<port>`
 * and passes on what it answered. The ping half is absent — not zero — whenever the plugin is
 * connected (there is a better answer already), whenever the process is not RUNNING, and whenever
 * the ping simply failed, so every one of those fields is nullable and means "not measured".
 *
 * [cpu]/[memRss] and [cpuPercent]/[rssBytes] are the same two numbers under the old and the new
 * names. A node sends both, which is what lets either side of the pair be updated without a
 * flag day; Pano prefers the §2.4.17 names and falls back to the originals.
 */
data class ServerProcessMetricsEventRequest(
    val serverUuid: String? = null,
    val t: Long? = null,
    val cpu: Double? = null,
    val memRss: Long? = null,
    /** CPU percentage of the process, §2.4.17's name for [cpu]. */
    val cpuPercent: Double? = null,
    /** Resident memory of the process in bytes, §2.4.17's name for [memRss]. */
    val rssBytes: Long? = null,
    /** Players online, exactly as the ping reported it — the count is not a sample. */
    val playerCount: Long? = null,
    val maxPlayers: Long? = null,
    /** Usernames the ping happened to include, capped at twelve by the protocol itself. */
    val playerSample: List<String>? = null,
    val motd: String? = null,
    /** Server version as the ping names it, e.g. `Paper 1.21.1`. */
    val versionName: String? = null,
    /**
     * Bytes the server's directory takes on the node's disk (§2.4.18 A).
     *
     * Null until the node's first walk of that directory finishes, and null again for a while
     * after a restore, an import, a reinstall or a delete threw the previous figure away — a
     * measurement that has not landed is not a server that takes no space, so it is never read as
     * a zero.
     */
    val diskBytes: Long? = null,
    /**
     * Total size of the partition that directory is on, in bytes (§2.4.18, revision of
     * 2026-09-22).
     *
     * One system call on the node rather than a walk, so it is sent on every tick and is usually
     * there long before [diskBytes] is. Null means the node could not read it, never a disk of no
     * size.
     */
    val diskTotalBytes: Long? = null,
    /**
     * The server's own traffic in bytes per second (§2.4.22 A): only a container has a network
     * stack of its own to count, so this is null for a plain process — and Pano then falls back to
     * the node's host figure, labelled as such. Only a running server's frame carries it.
     */
    val netRxBps: Long? = null,
    val netTxBps: Long? = null
) : NodeEventRequest() {
    /** Process CPU under whichever of the two names the node used. */
    val processCpu get() = cpuPercent ?: cpu

    /** Resident memory under whichever of the two names the node used. */
    val residentBytes get() = rssBytes ?: memRss

    /** Whether this frame carries a server list ping at all. */
    val hasPing get() = playerCount != null || playerSample != null || motd != null

    /**
     * Whether this is a stopped server's frame: a directory size and nothing else (§2.4.18 A).
     *
     * A node reports every server it holds, running or not, because the files of a server that is
     * off are still on its disk — but a frame for one carries no process reading, no ping and no
     * roster, and Pano has to keep it out of the live sample entirely. Writing it as a sample
     * would make a switched-off server look like one reporting zero CPU, and the per-minute
     * recorder would draw that flat line into its history for as long as it stayed off.
     *
     * [diskTotalBytes] rides along in this frame too and changes nothing about the rule: the test
     * is that a size is there and no process reading is.
     *
     * Read off what is missing rather than a flag, because that is all the wire carries. The one
     * frame this misreads is a *running* server that could measure neither CPU nor resident
     * memory and has a plugin connected (so no ping either) — the first tick after a start, or a
     * Windows host, where resident memory has no portable API. The cost is one tick where the
     * live sample is not refreshed, which the next frame corrects.
     */
    val isDiskOnly get() = diskBytes != null && processCpu == null && residentBytes == null && !hasPing &&
        netRxBps == null && netTxBps == null
}
