package com.panomc.node.host

import java.io.File

/**
 * The host's network traffic, as the kernel counts it (§2.4.22 A): the sum of the physical
 * interfaces, virtual ones excluded.
 *
 * Linux only, because `/proc/net/dev` is the one place every distribution keeps these counters and
 * the JDK has nothing equivalent; elsewhere the answer is null rather than a guess. The counters are
 * cumulative bytes since boot — a rate comes from two readings and a [ByteRate].
 *
 * Virtual interfaces are left out because every byte on them also crosses a physical one: a
 * container's download is counted on its veth, on the bridge and on eth0, and a VPN's on wg0 and
 * again, encrypted, on eth0. Summing all of them would report two or three times the traffic the
 * machine actually has. See [isCounted].
 */
object HostNetwork {
    private val PROC_NET_DEV = File("/proc/net/dev")

    /**
     * Name prefixes of interfaces whose traffic is already counted on a physical one: loopback,
     * container bridges and pairs (Docker, libvirt, CNI, Flannel, Calico) and tunnels (WireGuard,
     * tun/tap VPNs).
     */
    private val VIRTUAL_PREFIXES = listOf(
        "docker", "veth", "br-", "virbr", "vnet", "cni", "flannel", "cali", "wg", "tun", "tap"
    )

    /**
     * Whether [name] is an interface whose traffic belongs in the host's sum.
     *
     * Physical, bonded and VLAN interfaces are (`eth*`, `en*`, `wl*`, `bond*`, and anything this
     * list does not know, which is the safe side to err on); loopback and the virtual prefixes
     * above are not.
     */
    fun isCounted(name: String): Boolean =
        name != "lo" && VIRTUAL_PREFIXES.none { name.startsWith(it) }

    /** Received and transmitted bytes summed over every interface but loopback. */
    data class Totals(val rx: Long, val tx: Long)

    /** This host's totals right now, or null when this is not Linux or the file cannot be read. */
    fun read(): Totals? {
        if (!HostPlatform.isLinux) {
            return null
        }

        return try {
            parse(PROC_NET_DEV.readText())
        } catch (_: Exception) {
            null
        }
    }

    /**
     * `/proc/net/dev` as totals, or null when it holds no interface at all.
     *
     * Two header lines, then one line per interface: `name:` followed by eight receive and eight
     * transmit counters, bytes first in each group. Old kernels printed `eth0:1234` with no space
     * after the colon, which is why the name is split off at the colon rather than on whitespace.
     * A line that does not have the numbers it should is skipped, not allowed to zero the sum, and
     * only interfaces [isCounted] accepts are summed.
     */
    fun parse(text: String): Totals? {
        var rx = 0L
        var tx = 0L
        var any = false

        text.lineSequence().forEach { line ->
            val colon = line.indexOf(':')

            if (colon < 0) {
                return@forEach
            }

            val name = line.substring(0, colon).trim()

            // The header's second line has a `|` in it and no interface; so does nothing else.
            if (name.isEmpty() || name.contains('|') || !isCounted(name)) {
                return@forEach
            }

            val fields = line.substring(colon + 1).trim().split(Regex("\\s+"))

            val received = fields.getOrNull(RX_BYTES)?.toLongOrNull() ?: return@forEach
            val transmitted = fields.getOrNull(TX_BYTES)?.toLongOrNull() ?: return@forEach

            rx += received
            tx += transmitted
            any = true
        }

        return if (any) Totals(rx, tx) else null
    }

    private const val RX_BYTES = 0
    private const val TX_BYTES = 8
}
