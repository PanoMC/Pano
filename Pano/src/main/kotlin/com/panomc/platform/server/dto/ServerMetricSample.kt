package com.panomc.platform.server.dto

import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject

/**
 * The most recent performance sample a connected server reported.
 *
 * Samples arrive every 10 seconds whether or not anyone is watching, because they are cheap and
 * they are what the once-a-minute `server_metric` rollup is written from. Exactly one sample is
 * kept per server: history lives in the database, not in memory.
 *
 * [tps] holds the 1, 5 and 15 minute load averages in that order and is null on proxies, which
 * have no tick loop at all. [mspt] and [cpu] are null whenever the platform cannot measure them.
 */
data class ServerMetricSample(
    val t: Long,
    val tps: List<Double>?,
    val mspt: Double?,
    val memUsed: Long,
    val memMax: Long,
    val cpu: Double?,
    val playerCount: Long,
    val maxPlayerCount: Long,
    val players: List<ServerMetricPlayerData>,
    /**
     * Host-side CPU of the server process, as the node measures it. Null for a linked server,
     * where nothing on this side can see the process at all.
     */
    val processCpu: Double? = null,
    /** Resident memory of the server process in bytes, reported by the node. */
    val memRss: Long? = null,
    /**
     * Who measured this sample: [SOURCE_PLUGIN] from inside the game, [SOURCE_NODE] from the
     * process and a server list ping.
     *
     * The panel says so next to the numbers, because the two are not the same measurement — a
     * node's memory figure is the whole JVM's resident set and its player list is a twelve-name
     * sample — and a chart that silently mixed them would be a lie told in a straight line.
     */
    val source: String = SOURCE_PLUGIN,
    /** MOTD as the node's ping read it; only a node sample ever has one. */
    val motd: String? = null,
    /** Version string as the node's ping read it, e.g. `Paper 1.21.1`. */
    val versionName: String? = null,
    /**
     * Bytes the server's directory takes on disk, or null while nobody has measured it yet
     * (§2.4.18 A).
     *
     * Both sides can answer: the node walks the directory it manages, an agent-lite plugin walks
     * its own `getServerDirectory()`. Null is "not measured", never "empty".
     */
    val diskUsed: Long? = null,
    /**
     * Total size of the partition the server's directory is on, or null when nobody has read it
     * (§2.4.18, revision of 2026-09-22).
     *
     * The denominator of the panel's disk gauge: bytes used mean little without the disk they are
     * used on. Cheap for either reporter — one system call, no walk — so unlike [diskUsed] it is
     * usually there from the very first frame, and it is never written to the metric history
     * because it is a fact about the host, not about that minute.
     */
    val diskTotal: Long? = null,
    /**
     * Whether [diskUsed] is the node's measurement rather than the plugin's (§2.4.18 B).
     *
     * Only ever read by the next sample, to decide which of two figures for the same directory
     * survives: the node measures it from outside the process, which is the host's truth, so the
     * plugin's own number fills the gap and does not replace it. Deliberately not in
     * [toJsonObject] — it changes nothing the panel draws.
     */
    val diskUsedFromNode: Boolean = false,
    /**
     * The same for [diskTotal], tracked apart from [diskUsedFromNode] because the two halves of
     * the pair genuinely arrive apart: the node knows the partition on its first tick and the
     * size of the directory only once a walk has finished, so a sample can hold the node's total
     * next to the plugin's used, and neither flag may speak for the other.
     */
    val diskTotalFromNode: Boolean = false,
    /**
     * Received traffic in bytes per second over the last tick, or null when unknown (§2.4.22 A).
     *
     * Whose traffic it is is [netScope]'s to say, and it is not always the server's own: a
     * container can be counted, a plain process cannot, and then the figure is its node's.
     */
    val netRx: Long? = null,
    /** Sent traffic in bytes per second, with the same caveats as [netRx]. */
    val netTx: Long? = null,
    /**
     * [NET_SCOPE_SERVER] when [netRx]/[netTx] are the server's own traffic, [NET_SCOPE_NODE] when
     * they are the whole host's because the server's own could not be counted, null when there is
     * no figure at all. The panel labels the second "node total" so a server is never credited
     * with every other server's traffic without saying so.
     */
    val netScope: String? = null
) {
    /** The 1 minute TPS average, which is what the per-minute rollup stores. */
    val tps1: Double? get() = tps?.firstOrNull()

    /**
     * This sample with the node's view of the process merged in.
     *
     * The two halves arrive independently — the plugin reports what the game sees, the node what
     * the operating system sees — and neither is allowed to erase the other, so this only ever
     * overwrites the two process fields, the timestamp and, when the node has one, the size of
     * the server's directory.
     */
    fun withProcessMetrics(
        t: Long,
        processCpu: Double?,
        memRss: Long?,
        diskUsed: Long? = null,
        diskTotal: Long? = null
    ) = copy(
        t = t,
        processCpu = processCpu,
        memRss = memRss,
        // The node's figures win over the plugin's for the same directory, but a tick whose walk
        // has not finished yet carries no size at all, and that must not erase one.
        diskUsed = diskUsed ?: this.diskUsed,
        diskTotal = diskTotal ?: this.diskTotal,
        diskUsedFromNode = diskUsed != null || this.diskUsedFromNode,
        diskTotalFromNode = diskTotal != null || this.diskTotalFromNode
    )

    /**
     * This sample with a newly measured directory size and nothing else touched — [t] above all
     * (§2.4.18 A).
     *
     * Used for the frame a node sends about a server that is *not* running: the size is news, the
     * server is not, and moving the timestamp would tell the per-minute recorder that a stopped
     * server is still reporting and have it write a row a minute for a process that does not
     * exist.
     */
    fun withDiskUsage(diskUsed: Long?, diskTotal: Long?) =
        if (diskUsed == null && diskTotal == null) {
            this
        } else {
            copy(
                diskUsed = diskUsed ?: this.diskUsed,
                diskTotal = diskTotal ?: this.diskTotal,
                diskUsedFromNode = diskUsed != null || this.diskUsedFromNode,
                diskTotalFromNode = diskTotal != null || this.diskTotalFromNode
            )
        }

    /**
     * This sample with the traffic figure of one running tick (§2.4.22 A).
     *
     * The server's own rates when the node could count them ([ownRx]/[ownTx], a container), else
     * the host's ([hostRx]/[hostTx], every server on that node together), else nothing. Replaced
     * every tick rather than kept like the disk figure: a rate is a statement about the last ten
     * seconds, and the one before is not a better answer than "unknown".
     */
    fun withNetwork(ownRx: Long?, ownTx: Long?, hostRx: Long?, hostTx: Long?): ServerMetricSample = when {
        ownRx != null || ownTx != null -> copy(netRx = ownRx, netTx = ownTx, netScope = NET_SCOPE_SERVER)

        hostRx != null || hostTx != null -> copy(netRx = hostRx, netTx = hostTx, netScope = NET_SCOPE_NODE)

        else -> copy(netRx = null, netTx = null, netScope = null)
    }

    /**
     * This freshly reported plugin sample with the node's last process figures from [previous].
     *
     * The plugin measures the game, the node measures the process, and at a fast rate their frames
     * arrive a moment apart: without this every plugin sample would blank the CPU and resident
     * memory the node reported a moment before, and the live numbers would flicker between the two.
     */
    fun keepingProcessOf(previous: ServerMetricSample?): ServerMetricSample =
        copy(processCpu = previous?.processCpu, memRss = previous?.memRss)

    /**
     * This freshly reported plugin sample with the traffic figure the node last put on [previous].
     *
     * The plugin measures no traffic at all, and it reports as often as the node does, so a plugin
     * sample that simply had none would blank the network card every other update.
     */
    fun keepingNetOf(previous: ServerMetricSample?): ServerMetricSample =
        copy(netRx = previous?.netRx, netTx = previous?.netTx, netScope = previous?.netScope)

    /**
     * This freshly reported plugin sample, with the disk figure whoever measured it better keeps
     * (§2.4.18 B).
     *
     * Two reporters can measure the same directory: the node walks it from outside the process,
     * the plugin walks what its own API calls the server directory. When both have an answer the
     * node's wins, and when this sample has none — the plugin's walk has not finished, or the
     * plugin is too old to send one — whatever was measured last stands rather than the card
     * blinking back to a dash every ten seconds.
     */
    fun keepingDiskOf(previous: ServerMetricSample?): ServerMetricSample {
        val nodeUsed = previous?.takeIf { it.diskUsedFromNode }?.diskUsed
        val nodeTotal = previous?.takeIf { it.diskTotalFromNode }?.diskTotal

        return copy(
            diskUsed = nodeUsed ?: diskUsed ?: previous?.diskUsed,
            diskTotal = nodeTotal ?: diskTotal ?: previous?.diskTotal,
            diskUsedFromNode = nodeUsed != null,
            diskTotalFromNode = nodeTotal != null
        )
    }

    companion object {
        const val SOURCE_PLUGIN = "plugin"
        const val SOURCE_NODE = "node"

        /** [netScope]: the server's own traffic. */
        const val NET_SCOPE_SERVER = "server"

        /** [netScope]: the whole host's traffic, standing in for a server that cannot be counted. */
        const val NET_SCOPE_NODE = "node"

        /** Names a server list ping may carry, which is the protocol's own cap. */
        const val MAX_PLAYER_SAMPLE = 12
    }

    fun toJsonObject(): JsonObject = JsonObject()
        .put("t", t)
        .put("tps", tps?.let { JsonArray(it) })
        .put("mspt", mspt)
        .put("memUsed", memUsed)
        .put("memMax", memMax)
        .put("cpu", cpu)
        .put("playerCount", playerCount)
        .put("maxPlayerCount", maxPlayerCount)
        .put("players", JsonArray(players.map { player ->
            JsonObject()
                .put("uuid", player.uuid)
                .put("username", player.username)
                .put("ping", player.ping)
                .put("op", player.op)
                .put("whitelisted", player.whitelisted)
                .put("gamemode", player.gamemode)
        }))
        .put("processCpu", processCpu)
        .put("memRss", memRss)
        .put("source", source)
        .put("motd", motd)
        .put("versionName", versionName)
        .put("diskUsed", diskUsed)
        .put("diskTotal", diskTotal)
        .put("netRx", netRx)
        .put("netTx", netTx)
        // Absent rather than null when there is no figure: there is nothing to say whose it is.
        .apply { netScope?.let { put("netScope", it) } }
}
