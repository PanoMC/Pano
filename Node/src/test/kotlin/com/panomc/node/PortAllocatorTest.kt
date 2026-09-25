package com.panomc.node

import com.panomc.node.host.HostPlatform
import com.panomc.node.server.PortAllocator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

class PortAllocatorTest {
    @Test
    fun `takes the first port in the range`() {
        assertEquals(25565, PortAllocator.allocate(25565..25570, emptySet()) { true })
    }

    @Test
    fun `skips ports another server on this node already holds`() {
        assertEquals(25567, PortAllocator.allocate(25565..25570, setOf(25565, 25566)) { true })
    }

    @Test
    fun `skips ports something outside this node is already bound to`() {
        val bound = setOf(25565, 25566, 25567)

        assertEquals(25568, PortAllocator.allocate(25565..25570, emptySet()) { it !in bound })
    }

    @Test
    fun `returns null when the whole range is spoken for`() {
        assertNull(PortAllocator.allocate(25565..25567, setOf(25565, 25566, 25567)) { true })
    }

    @Test
    fun `honours both reasons a port can be unavailable at once`() {
        assertEquals(25569, PortAllocator.allocate(25565..25570, setOf(25565, 25568)) { it !in setOf(25566, 25567) })
    }

    @Test
    fun `keeps the port Pano asked for when nothing on the host holds it`() {
        val resolution = PortAllocator.resolve(25565, 25565..25570, emptySet(), emptySet()) { true }

        assertEquals(25565, resolution?.port)
        assertFalse(resolution!!.changed)
        assertNull(resolution.requestedPort)
    }

    @Test
    fun `reallocates when something outside this node is bound to the asked-for port`() {
        val resolution = PortAllocator.resolve(25565, 25565..25570, emptySet(), emptySet()) { it != 25565 }

        assertEquals(25566, resolution?.port)
        assertTrue(resolution!!.changed)
        assertEquals(25565, resolution.requestedPort)
        assertTrue(resolution.reason!!.contains("already in use on this host"))
    }

    @Test
    fun `reallocates around another server of this node and around a task in flight`() {
        val byServer = PortAllocator.resolve(25565, 25565..25570, setOf(25565), emptySet()) { true }

        assertEquals(25566, byServer?.port)
        assertTrue(byServer!!.reason!!.contains("another server on this node"))

        val byTask = PortAllocator.resolve(25565, 25565..25570, emptySet(), setOf(25565)) { true }

        assertEquals(25566, byTask?.port)
        assertTrue(byTask!!.reason!!.contains("another task on this node"))
    }

    @Test
    fun `never hands back the port it just refused`() {
        val resolution = PortAllocator.resolve(25567, 25565..25570, emptySet(), emptySet()) { it != 25567 }

        assertEquals(25565, resolution?.port)
    }

    @Test
    fun `a port of zero is you pick one and is not a change to report`() {
        val resolution = PortAllocator.resolve(0, 25565..25570, setOf(25565), emptySet()) { true }

        assertEquals(25566, resolution?.port)
        assertFalse(resolution!!.changed)
    }

    @Test
    fun `gives up rather than reusing a port when the range is exhausted`() {
        assertNull(PortAllocator.resolve(25565, 25565..25566, emptySet(), emptySet()) { false })
    }

    @Test
    fun `moves a port Pano asked for outside the node's range into it`() {
        // The live bug: a container publishing 25660-25669 was asked for 25565 and bound it.
        val resolution = PortAllocator.resolve(25565, 25660..25669, emptySet(), emptySet()) { true }

        assertEquals(25660, resolution?.port)
        assertTrue(resolution!!.changed)
        assertEquals(25565, resolution.requestedPort)
        assertTrue(resolution.reason!!.contains("outside this node's port range 25660-25669"), resolution.reason)
    }

    @Test
    fun `the move into the range still walks around taken, reserved and bound ports`() {
        val resolution = PortAllocator.resolve(25565, 25660..25669, setOf(25660), setOf(25661)) { it != 25662 }

        assertEquals(25663, resolution?.port)
        assertTrue(resolution!!.changed)
    }

    @Test
    fun `a port outside a full range is refused rather than bound outside it`() {
        assertNull(PortAllocator.resolve(25565, 25660..25661, setOf(25660, 25661), emptySet()) { true })
    }

    @Test
    fun `a port inside the range is not moved for the range`() {
        val resolution = PortAllocator.resolve(25665, 25660..25669, emptySet(), emptySet()) { true }

        assertEquals(25665, resolution?.port)
        assertFalse(resolution!!.changed)
    }

    @Test
    fun `a port an adopted directory already uses may stay outside the range`() {
        val kept = PortAllocator.resolve(30000, 25565..25600, emptySet(), emptySet(), allowOutsideRange = true) { true }

        assertEquals(30000, kept?.port)
        assertFalse(kept!!.changed)

        // Still moved when something else holds it, and then into the range.
        val moved = PortAllocator.resolve(30000, 25565..25600, emptySet(), emptySet(), allowOutsideRange = true) { it != 30000 }

        assertEquals(25565, moved?.port)
        assertTrue(moved!!.changed)
    }

    @Test
    fun `a port a stopped server left in TIME_WAIT is free`() {
        assumeFalse(HostPlatform.isWindows)

        val port = ServerSocket().use { listener ->
            listener.reuseAddress = true
            listener.bind(InetSocketAddress(0))

            Socket("127.0.0.1", listener.localPort).use { client ->
                // The server hangs up first, as it does to its players when it stops, which
                // leaves the connection in TIME_WAIT on the server's own port.
                listener.accept().close()
                Thread.sleep(100)
            }

            listener.localPort
        }

        Thread.sleep(100)

        assertTrue(PortAllocator.isPortFree(port))
    }

    @Test
    fun `a port something is listening on is not free`() {
        ServerSocket(0).use { listener ->
            assertFalse(PortAllocator.isPortFree(listener.localPort))
        }
    }
}
