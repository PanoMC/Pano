package com.panomc.platform.server.dto

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The two-reporter merge rules of §2.4.18 B, which are the whole reason a sample is merged rather
 * than replaced: the node and the plugin measure different things, and each of them regularly has
 * nothing to say about a field the other just measured.
 */
class ServerMetricSampleTest {
    @Test
    fun `the node's process figures land on the plugin's sample without touching it`() {
        val merged = pluginSample().withProcessMetrics(t = 200, processCpu = 12.5, memRss = 4096)

        assertEquals(200, merged.t)
        assertEquals(12.5, merged.processCpu)
        assertEquals(4096, merged.memRss)
        // What the plugin measured inside the game is untouched.
        assertEquals(50, merged.memUsed)
        assertEquals(ServerMetricSample.SOURCE_PLUGIN, merged.source)
    }

    @Test
    fun `the node's disk figure wins over the plugin's for the same directory`() {
        val merged = pluginSample(diskUsed = 1_000)
            .withProcessMetrics(100, null, null, diskUsed = 2_000, diskTotal = 500_107_862_016)

        assertEquals(2_000, merged.diskUsed)
        assertEquals(500_107_862_016, merged.diskTotal)
        assertTrue(merged.diskUsedFromNode)
        assertTrue(merged.diskTotalFromNode)
    }

    @Test
    fun `a node tick with no measurement yet does not erase the plugin's figure`() {
        val merged = pluginSample(diskUsed = 1_000).withProcessMetrics(100, 1.0, 2, diskUsed = null)

        assertEquals(1_000, merged.diskUsed)
        assertFalse(merged.diskUsedFromNode, "nothing the node measured is on this sample")
    }

    @Test
    fun `the node can know the disk before it knows the directory`() {
        // The partition is one system call and the walk takes minutes, so this is what every
        // managed server looks like for its first five minutes.
        val merged = pluginSample(diskUsed = 1_000)
            .withProcessMetrics(100, 1.0, 2, diskUsed = null, diskTotal = 500_107_862_016)

        assertEquals(1_000, merged.diskUsed)
        assertFalse(merged.diskUsedFromNode)
        assertEquals(500_107_862_016, merged.diskTotal)
        assertTrue(merged.diskTotalFromNode)
    }

    @Test
    fun `a node tick between two walks keeps the node figure it already merged in`() {
        val withDisk = pluginSample().withProcessMetrics(100, 1.0, 2, diskUsed = 8_000, diskTotal = 500)
        val nextTick = withDisk.withProcessMetrics(110, 1.5, 3, diskUsed = null, diskTotal = null)

        assertEquals(8_000, nextTick.diskUsed)
        assertEquals(500, nextTick.diskTotal)
        assertTrue(nextTick.diskUsedFromNode)
        assertTrue(nextTick.diskTotalFromNode)
    }

    @Test
    fun `a fresh plugin sample does not replace what the node measured`() {
        val previous = pluginSample().withProcessMetrics(100, 1.0, 2, diskUsed = 8_000, diskTotal = 500)

        val next = pluginSample(diskUsed = 7_777).copy(diskTotal = 400).keepingDiskOf(previous)

        assertEquals(8_000, next.diskUsed)
        assertEquals(500, next.diskTotal)
        assertTrue(next.diskUsedFromNode)
        assertTrue(next.diskTotalFromNode)
    }

    @Test
    fun `a plugin still fills the half of the pair the node has not measured`() {
        // The node knows the partition from its first tick and the directory only later; the
        // plugin's own size must not be shut out by the flag on the other half.
        val previous = pluginSample().withProcessMetrics(100, 1.0, 2, diskUsed = null, diskTotal = 500)

        val next = pluginSample(diskUsed = 7_777).keepingDiskOf(previous)

        assertEquals(7_777, next.diskUsed)
        assertFalse(next.diskUsedFromNode)
        assertEquals(500, next.diskTotal)
        assertTrue(next.diskTotalFromNode)
    }

    @Test
    fun `a plugin measures the disk itself when no node ever did`() {
        val next = pluginSample(diskUsed = 7_777).keepingDiskOf(pluginSample(diskUsed = 100))

        assertEquals(7_777, next.diskUsed)
        assertFalse(next.diskUsedFromNode)
    }

    @Test
    fun `a plugin sample with no figure keeps the last one rather than blanking the card`() {
        val next = pluginSample(diskUsed = null).keepingDiskOf(pluginSample(diskUsed = 4_321))

        assertEquals(4_321, next.diskUsed)
    }

    @Test
    fun `the first sample of all simply has no figure`() {
        val next = pluginSample(diskUsed = null).keepingDiskOf(null)

        assertNull(next.diskUsed)
        assertNull(next.diskTotal)
        assertFalse(next.diskUsedFromNode)
        assertFalse(next.diskTotalFromNode)
    }

    @Test
    fun `a stopped server's size lands on the sample without making it look fresh`() {
        val previous = pluginSample(diskUsed = 1_000).copy(t = 42)

        val updated = previous.withDiskUsage(9_000, 500_107_862_016)

        assertEquals(9_000, updated.diskUsed)
        assertEquals(500_107_862_016, updated.diskTotal)
        assertTrue(updated.diskUsedFromNode)
        assertTrue(updated.diskTotalFromNode)
        // The timestamp above all: a moved one would tell the per-minute recorder that a server
        // which is switched off is still reporting.
        assertEquals(42, updated.t)
        assertEquals(previous.memUsed, updated.memUsed)
        assertEquals(previous.playerCount, updated.playerCount)
    }

    @Test
    fun `a stopped server with no measurement yet changes nothing`() {
        val previous = pluginSample(diskUsed = 1_000)

        assertEquals(previous, previous.withDiskUsage(null, null))
    }

    @Test
    fun `a server's own traffic is labelled as its own`() {
        val sample = pluginSample().withNetwork(ownRx = 5_000, ownTx = 250, hostRx = 90_000, hostTx = 80_000)

        assertEquals(5_000, sample.netRx)
        assertEquals(250, sample.netTx)
        assertEquals(ServerMetricSample.NET_SCOPE_SERVER, sample.netScope)
    }

    @Test
    fun `a server that cannot be counted falls back to its node's traffic, and says so`() {
        val sample = pluginSample().withNetwork(ownRx = null, ownTx = null, hostRx = 90_000, hostTx = 80_000)

        assertEquals(90_000, sample.netRx)
        assertEquals(80_000, sample.netTx)
        assertEquals(ServerMetricSample.NET_SCOPE_NODE, sample.netScope)
    }

    @Test
    fun `half of the server's own figure is still the server's, never mixed with the host's`() {
        val sample = pluginSample().withNetwork(ownRx = 5_000, ownTx = null, hostRx = 90_000, hostTx = 80_000)

        assertEquals(5_000, sample.netRx)
        assertNull(sample.netTx)
        assertEquals(ServerMetricSample.NET_SCOPE_SERVER, sample.netScope)
    }

    @Test
    fun `no figure anywhere is no figure, and a rate is never kept past its tick`() {
        val earlier = pluginSample().withNetwork(5_000, 250, null, null)
        val now = earlier.withNetwork(null, null, null, null)

        assertNull(now.netRx)
        assertNull(now.netTx)
        assertNull(now.netScope)
        assertFalse(now.toJsonObject().containsKey("netScope"), "no figure, so nothing to say whose it is")
        assertTrue(now.toJsonObject().containsKey("netRx"))
    }

    @Test
    fun `a plugin sample keeps the node's process figures instead of blanking them`() {
        // The node's frame, then the plugin's a moment later: the live CPU and resident memory must
        // not flicker to nothing on every plugin frame.
        val previous = pluginSample().withProcessMetrics(t = 100, processCpu = 37.5, memRss = 3_221_225_472)

        val next = pluginSample().keepingProcessOf(previous)

        assertEquals(37.5, next.processCpu)
        assertEquals(3_221_225_472, next.memRss)
        // What the plugin itself measured is its own.
        assertEquals(50, next.memUsed)

        assertNull(pluginSample().keepingProcessOf(null).processCpu)
    }

    @Test
    fun `a plugin sample keeps the traffic the node last reported instead of blanking it`() {
        val previous = pluginSample().withNetwork(null, null, 90_000, 80_000)

        val next = pluginSample().keepingNetOf(previous)

        assertEquals(90_000, next.netRx)
        assertEquals(80_000, next.netTx)
        assertEquals(ServerMetricSample.NET_SCOPE_NODE, next.netScope)

        // A linked server with no node never had one.
        assertNull(pluginSample().keepingNetOf(null).netScope)
    }

    @Test
    fun `traffic goes out under the names the panel reads`() {
        val json = pluginSample().withNetwork(5_000, 250, null, null).toJsonObject()

        assertEquals(5_000L, json.getLong("netRx"))
        assertEquals(250L, json.getLong("netTx"))
        assertEquals("server", json.getString("netScope"))
    }

    @Test
    fun `disk goes out under the name the panel reads, and the bookkeeping flag does not`() {
        val json = pluginSample(diskUsed = 1_234)
            .withProcessMetrics(1, null, null, 5_678, 500_107_862_016)
            .toJsonObject()

        assertEquals(5_678L, json.getLong("diskUsed"))
        assertEquals(500_107_862_016L, json.getLong("diskTotal"))
        assertFalse(json.containsKey("diskUsedFromNode"))
        assertFalse(json.containsKey("diskTotalFromNode"))
    }

    @Test
    fun `a sample nobody measured the disk of says so instead of saying zero`() {
        val json = pluginSample().toJsonObject()

        assertTrue(json.containsKey("diskUsed"))
        assertNull(json.getLong("diskUsed"))
        assertTrue(json.containsKey("diskTotal"))
        assertNull(json.getLong("diskTotal"))
    }

    private fun pluginSample(diskUsed: Long? = null) = ServerMetricSample(
        t = 1,
        tps = listOf(20.0, 20.0, 20.0),
        mspt = 3.0,
        memUsed = 50,
        memMax = 100,
        cpu = 5.0,
        playerCount = 2,
        maxPlayerCount = 20,
        players = emptyList(),
        diskUsed = diskUsed
    )
}
