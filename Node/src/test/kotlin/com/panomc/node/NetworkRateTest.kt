package com.panomc.node

import com.panomc.node.host.ByteRate
import com.panomc.node.host.HostNetwork
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Network traffic on the node (§2.4.22 A): the kernel's counters read out of `/proc/net/dev`, and a
 * rate made out of two readings of any cumulative counter.
 */
class NetworkRateTest {
    /** A real `/proc/net/dev` shape: two header lines, loopback, a NIC and a docker bridge. */
    private val procNetDev = """
        Inter-|   Receive                                                |  Transmit
         face |bytes    packets errs drop fifo frame compressed multicast|bytes    packets errs drop fifo colls carrier compressed
            lo: 9999999    1000    0    0    0     0          0         0  9999999    1000    0    0    0     0       0          0
          eth0: 1000000    2000    0    0    0     0          0         0   500000    1500    0    0    0     0       0          0
        docker0:   20000     100    0    0    0     0          0         0    40000     120    0    0    0     0       0          0
    """.trimIndent()

    @Test
    fun `physical interfaces are summed, receive and transmit apart, virtual ones left out`() {
        // docker0 carries the same bytes eth0 already counted.
        assertEquals(HostNetwork.Totals(rx = 1_000_000, tx = 500_000), HostNetwork.parse(procNetDev))
    }

    @Test
    fun `which interfaces count`() {
        val table = mapOf(
            // Physical, bonded, VLAN and anything unknown: counted.
            "eth0" to true, "ens18" to true, "enp3s0" to true, "eno1" to true, "wlan0" to true,
            "wlp2s0" to true, "bond0" to true, "eth0.100" to true, "ib0" to true,
            // Loopback, container and VM plumbing, tunnels: already counted on a physical one.
            "lo" to false, "docker0" to false, "veth3a9f2c1" to false, "br-5d1e8f7a2b3c" to false,
            "virbr0" to false, "vnet0" to false, "cni0" to false, "flannel.1" to false,
            "cali1234abcd" to false, "wg0" to false, "tun0" to false, "tap0" to false
        )

        table.forEach { (name, counted) -> assertEquals(counted, HostNetwork.isCounted(name), name) }
    }

    @Test
    fun `an old kernel's name glued to its first counter is still read`() {
        val text = "Inter-| header\n face |bytes\n  eth0:1234 1 0 0 0 0 0 0 5678 2 0 0 0 0 0 0"

        assertEquals(HostNetwork.Totals(rx = 1234, tx = 5678), HostNetwork.parse(text))
    }

    @Test
    fun `a line without its counters is skipped rather than zeroing the sum`() {
        val text = procNetDev + "\n  eth1: garbage\n  eth2: 1 2 3"

        assertEquals(HostNetwork.Totals(rx = 1_000_000, tx = 500_000), HostNetwork.parse(text))
    }

    @Test
    fun `nothing but loopback and virtual interfaces, or nothing at all, is no answer`() {
        assertNull(HostNetwork.parse("Inter-|\n face |\n    lo: 1 0 0 0 0 0 0 0 1 0 0 0 0 0 0 0"))
        assertNull(HostNetwork.parse("Inter-|\n face |\n  docker0: 1 0 0 0 0 0 0 0 1 0 0 0 0 0 0 0"))
        assertNull(HostNetwork.parse(""))
    }

    @Test
    fun `the first reading has nothing to compare with`() {
        assertNull(ByteRate().rate(1_000, at = 0))
    }

    @Test
    fun `a rate is the difference over the time between two readings`() {
        val meter = ByteRate()

        meter.rate(1_000, at = 0)

        // 50 000 bytes in ten seconds.
        assertEquals(5_000L, meter.rate(51_000, at = 10_000))

        // And the next tick measures from this one, not from the first.
        assertEquals(100L, meter.rate(52_000, at = 20_000))
    }

    @Test
    fun `a counter that went backwards was reset, so that tick is null and the next one counts again`() {
        val meter = ByteRate()

        meter.rate(1_000_000, at = 0)

        assertNull(meter.rate(500, at = 10_000), "a container restart resets the counter")
        assertEquals(50L, meter.rate(1_000, at = 20_000))
    }

    @Test
    fun `a missing reading breaks the chain instead of spanning the gap`() {
        val meter = ByteRate()

        meter.rate(1_000, at = 0)

        assertNull(meter.rate(null, at = 10_000))
        assertNull(meter.rate(900_000, at = 20_000), "no rate across the reading that was missing")
        assertEquals(10L, meter.rate(900_100, at = 30_000))
    }

    @Test
    fun `no time between two readings is no rate`() {
        val meter = ByteRate()

        meter.rate(1_000, at = 5_000)

        assertNull(meter.rate(2_000, at = 5_000))
    }

    @Test
    fun `a reset starts over`() {
        val meter = ByteRate()

        meter.rate(1_000, at = 0)
        meter.reset()

        assertNull(meter.rate(2_000, at = 10_000))
    }
}
