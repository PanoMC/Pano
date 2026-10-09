package com.panomc.platform.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ProxyDetectorTest {
    private var now = 1_000_000L
    private val detector = ProxyDetector().also { it.clock = { now } }

    @Test
    fun `quiet traffic is OK`() {
        repeat(10) { detector.observe("203.0.113.9", "203.0.113.9", null, false, false) }

        val status = detector.status()
        assertEquals("OK", status.state)
        assertNull(status.suggestion)
    }

    @Test
    fun `untrusted peer with many forwarded values is flagged`() {
        repeat(25) { i -> detector.observe("203.0.113.9", "203.0.113.9", "198.51.100.${i % 6}", true, false) }

        val status = detector.status()
        assertEquals("UNTRUSTED_PROXY", status.state)
        assertEquals(listOf("203.0.113.9"), status.peers)
        assertEquals("trusted-proxies = [\"203.0.113.9\"]", status.suggestion)
    }

    @Test
    fun `trusted peer is never flagged as untrusted`() {
        repeat(25) { i -> detector.observe("10.0.0.2", "198.51.100.${i % 6}", "198.51.100.${i % 6}", true, true) }

        assertEquals("OK", detector.status().state)
    }

    @Test
    fun `too few distinct values is not flagged`() {
        repeat(40) { i -> detector.observe("203.0.113.9", "203.0.113.9", "198.51.100.${i % 3}", true, false) }

        assertEquals("OK", detector.status().state)
    }

    @Test
    fun `one private address for nearly all requests is a silent proxy`() {
        repeat(100) { detector.observe("10.0.0.2", "10.0.0.2", null, false, true) }

        val status = detector.status()
        assertEquals("SINGLE_CLIENT_IP", status.state)
        assertEquals(listOf("10.0.0.2"), status.peers)
    }

    @Test
    fun `one public address is not a silent proxy`() {
        repeat(150) { detector.observe("203.0.113.9", "203.0.113.9", null, false, false) }

        assertEquals("OK", detector.status().state)
    }

    @Test
    fun `direct loopback traffic is ignored`() {
        repeat(200) { detector.observe("127.0.0.1", "127.0.0.1", null, false, true) }

        assertEquals("OK", detector.status().state)
    }

    @Test
    fun `state clears after the window expires`() {
        repeat(25) { i -> detector.observe("203.0.113.9", "203.0.113.9", "198.51.100.${i % 6}", true, false) }
        assertEquals("UNTRUSTED_PROXY", detector.status().state)

        now += ProxyDetector.WINDOW_MS + 1

        assertEquals("OK", detector.status().state)
    }

    @Test
    fun `at most 256 peers are tracked`() {
        repeat(300) { i ->
            val peer = "203.0.${i / 200}.${i % 200 + 1}"
            repeat(25) { j -> detector.observe(peer, peer, "198.51.100.$j", true, false) }
        }

        assertEquals(ProxyDetector.MAX_PEERS, detector.status().peers.size)
    }
}
