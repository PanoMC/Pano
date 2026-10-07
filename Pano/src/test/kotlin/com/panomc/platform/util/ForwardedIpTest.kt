package com.panomc.platform.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ForwardedIpTest {
    private fun resolve(
        peer: String?,
        xff: String? = null,
        realIp: String? = null,
        trusted: List<String> = emptyList()
    ) = TrustedProxyIpResolver.resolveClientIp(peer, xff, realIp, trusted)

    @Test
    fun `public peer cannot declare a client address`() {
        assertEquals("203.0.113.9", resolve("203.0.113.9", xff = "1.2.3.4"))
        assertEquals("203.0.113.9", resolve("203.0.113.9", xff = "127.0.0.1", realIp = "127.0.0.1"))
    }

    @Test
    fun `loopback peer is trusted without configuration`() {
        assertEquals("198.51.100.7", resolve("127.0.0.1", xff = "198.51.100.7"))
        assertEquals("198.51.100.7", resolve("::1", xff = "198.51.100.7"))
        assertEquals("198.51.100.7", resolve("::ffff:127.0.0.1", xff = "198.51.100.7"))
    }

    @Test
    fun `private network peers are trusted without configuration`() {
        assertEquals("198.51.100.7", resolve("172.17.0.1", xff = "198.51.100.7"))
        assertEquals("198.51.100.7", resolve("10.1.2.3", xff = "198.51.100.7"))
        assertEquals("198.51.100.7", resolve("192.168.1.2", xff = "198.51.100.7"))
        assertEquals("198.51.100.7", resolve("fd12::1", xff = "198.51.100.7"))
    }

    @Test
    fun `leftmost hop written by the client is ignored, the rightmost untrusted hop wins`() {
        assertEquals("198.51.100.7", resolve("127.0.0.1", xff = "6.6.6.6, 198.51.100.7"))
    }

    @Test
    fun `configured proxies in the chain are skipped`() {
        assertEquals(
            "198.51.100.7",
            resolve("127.0.0.1", xff = "198.51.100.7, 203.0.113.50", trusted = listOf("203.0.113.50"))
        )
    }

    @Test
    fun `configured public proxy is trusted, cidr entries too`() {
        assertEquals("198.51.100.7", resolve("203.0.113.50", xff = "198.51.100.7", trusted = listOf("203.0.113.50")))
        assertEquals("198.51.100.7", resolve("173.245.48.9", xff = "198.51.100.7", trusted = listOf("173.245.48.0/20")))
        assertEquals("173.245.64.9", resolve("173.245.64.9", xff = "198.51.100.7", trusted = listOf("173.245.48.0/20")))
        assertEquals("198.51.100.7", resolve("2606:4700::1", xff = "198.51.100.7", trusted = listOf("2606:4700::/32")))
    }

    @Test
    fun `a garbage hop is never believed`() {
        assertEquals("127.0.0.1", resolve("127.0.0.1", xff = "198.51.100.7, unknown"))
        assertEquals("127.0.0.1", resolve("127.0.0.1", xff = "<script>"))
    }

    @Test
    fun `a LAN client behind a local proxy keeps its own address`() {
        assertEquals("192.168.1.50", resolve("127.0.0.1", xff = "192.168.1.50"))
    }

    @Test
    fun `X-Real-IP is used from a trusted peer when there is no X-Forwarded-For`() {
        assertEquals("198.51.100.7", resolve("127.0.0.1", realIp = "198.51.100.7"))
        assertEquals("203.0.113.9", resolve("203.0.113.9", realIp = "198.51.100.7"))
    }

    @Test
    fun `no headers means the socket peer, no peer means unknown`() {
        assertEquals("127.0.0.1", resolve("127.0.0.1"))
        assertEquals("unknown", resolve(null, xff = "1.2.3.4"))
    }

    @Test
    fun `ports and brackets are stripped from the peer and hops`() {
        assertEquals("198.51.100.7", resolve("127.0.0.1", xff = "198.51.100.7:4711"))
        assertEquals("2001:db8::7", resolve("127.0.0.1", xff = "[2001:db8::7]:443"))
    }

    @Test
    fun `isPrivateOrLoopback classifies addresses`() {
        assertTrue(TrustedProxyIpResolver.isPrivateOrLoopback("127.0.0.1"))
        assertTrue(TrustedProxyIpResolver.isPrivateOrLoopback("172.31.0.1"))
        assertTrue(TrustedProxyIpResolver.isPrivateOrLoopback("169.254.1.1"))
        assertFalse(TrustedProxyIpResolver.isPrivateOrLoopback("172.32.0.1"))
        assertFalse(TrustedProxyIpResolver.isPrivateOrLoopback("8.8.8.8"))
        assertFalse(TrustedProxyIpResolver.isPrivateOrLoopback("2606:4700::1"))
        assertFalse(TrustedProxyIpResolver.isPrivateOrLoopback("not-an-ip.local"))
    }

    @Test
    fun `loopback exemption needs a loopback socket and no forwarding header`() {
        assertTrue(RateLimitManager.isDirectLoopback("127.0.0.1", hasForwardingHeader = false))
        assertTrue(RateLimitManager.isDirectLoopback("::1", hasForwardingHeader = false))
        assertFalse(RateLimitManager.isDirectLoopback("127.0.0.1", hasForwardingHeader = true))
        assertFalse(RateLimitManager.isDirectLoopback("203.0.113.9", hasForwardingHeader = false))
        assertFalse(RateLimitManager.isDirectLoopback(null, hasForwardingHeader = false))
    }

    @Test
    fun `a spoofed 127_0_0_1 from a public peer is rate limited under the real socket address`() {
        val ip = resolve("203.0.113.9", xff = "127.0.0.1")

        assertEquals("203.0.113.9", ip)
        assertFalse(RateLimitManager.isDirectLoopback("203.0.113.9", hasForwardingHeader = true))
    }
}
