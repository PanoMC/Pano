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

    @Test
    fun `only strict address literals are believed as hops`() {
        listOf("zz:1:2", "abcd:1:2", "a1.zone.attacker.tld:1:2", "1:2:3:4:5:6:7:8:9", "::1::2", ":::", "12345::1", "1.2.3", "999.1.1.1")
            .forEach { assertEquals("127.0.0.1", resolve("127.0.0.1", xff = "198.51.100.7, $it"), it) }
    }

    @Test
    fun `junk next to a cidr entry is neither believed nor looked up`() {
        val trusted = listOf("173.245.48.0/20")

        assertEquals("127.0.0.1", resolve("127.0.0.1", xff = "a1.zone.attacker.tld:1:2", trusted = trusted))
        assertEquals("203.0.113.9", resolve("203.0.113.9", xff = "abcd:1:2", trusted = trusted))
        assertFalse(TrustedProxyIpResolver.isTrustedProxy("abcd:1:2", trusted))
        assertFalse(TrustedProxyIpResolver.isTrustedProxy("a1.zone.attacker.tld:1:2", listOf("2606:4700::/32")))
    }

    @Test
    fun `parseIp reads ipv4, ipv6, mapped and zoned literals and nothing else`() {
        assertEquals(listOf<Byte>(1, 2, 3, 4), TrustedProxyIpResolver.parseIp("1.2.3.4")?.toList())
        assertEquals(16, TrustedProxyIpResolver.parseIp("::1")?.size)
        assertEquals(16, TrustedProxyIpResolver.parseIp("2001:DB8:0:0:0:0:0:1")?.size)
        assertEquals(16, TrustedProxyIpResolver.parseIp("fe80::1%eth0")?.size)
        assertEquals(listOf<Byte>(1, 2, 3, 4), TrustedProxyIpResolver.parseIp("::ffff:1.2.3.4")?.toList())
        assertEquals(listOf<Byte>(1, 2, 3, 4), TrustedProxyIpResolver.parseIp("::ffff:102:304")?.toList())
        assertTrue(TrustedProxyIpResolver.parseIp("::1")!!.contentEquals(TrustedProxyIpResolver.parseIp("0:0:0:0:0:0:0:1")))
        listOf("", "localhost", "1.2.3.4.5", "fe80::1%", "fe80::1%e th", "g::1", "1:2:3:4:5:6:7::8").forEach {
            assertEquals(null, TrustedProxyIpResolver.parseIp(it), it)
        }
    }

    @Test
    fun `an address is matched against a listed one whatever its spelling`() {
        assertTrue(TrustedProxyIpResolver.isTrustedProxy("0:0:0:0:0:0:0:1", listOf("::1")))
        assertTrue(TrustedProxyIpResolver.isTrustedProxy("::ffff:203.0.113.50", listOf("203.0.113.50")))
        assertTrue(TrustedProxyIpResolver.isTrustedProxy("::ffff:173.245.48.9", listOf("173.245.48.0/20")))
        assertFalse(TrustedProxyIpResolver.isTrustedProxy("203.0.113.51", listOf("203.0.113.50")))
    }

    @Test
    fun `the cloudflare keyword trusts the published ranges and nothing else`() {
        val trusted = listOf("cloudflare")

        assertEquals(22, TrustedProxyIpResolver.CLOUDFLARE_RANGES.size)
        TrustedProxyIpResolver.CLOUDFLARE_RANGES.forEach { assertTrue(TrustedProxyIpResolver.isTrustedProxy(it.substringBefore('/'), trusted), it) }
        assertTrue(TrustedProxyIpResolver.isTrustedProxy("104.16.1.1", trusted))
        assertTrue(TrustedProxyIpResolver.isTrustedProxy("2606:4700::1", trusted))
        assertFalse(TrustedProxyIpResolver.isTrustedProxy("8.8.8.8", trusted))
        assertFalse(TrustedProxyIpResolver.isTrustedProxy("104.16.1.1", emptyList()))
        assertFalse(TrustedProxyIpResolver.isTrustedProxy("104.16.1.1", listOf("cloudflare.com")))
    }

    @Test
    fun `behind cloudflare the visitor is found with or without a local proxy in between`() {
        val trusted = listOf("Cloudflare")

        // Cloudflare -> Pano
        assertEquals("198.51.100.7", resolve("104.16.1.1", xff = "198.51.100.7", trusted = trusted))
        // Cloudflare -> nginx on localhost -> Pano ($proxy_add_x_forwarded_for)
        assertEquals("198.51.100.7", resolve("127.0.0.1", xff = "198.51.100.7, 104.16.1.1", trusted = trusted))
        // not listed: the edge address is all there is
        assertEquals("104.16.1.1", resolve("127.0.0.1", xff = "198.51.100.7, 104.16.1.1"))
        assertEquals("104.16.1.1", resolve("104.16.1.1", xff = "198.51.100.7"))
    }

    @Test
    fun `the proxy hint fires for an unlisted cloudflare edge or a chain with hops left of the chosen one`() {
        assertTrue(TrustedProxyIpResolver.needsProxyHint("104.16.1.1", "104.16.1.1", "198.51.100.7", "198.51.100.7"))
        assertTrue(TrustedProxyIpResolver.needsProxyHint("127.0.0.1", "104.16.1.1", "198.51.100.7, 104.16.1.1", null))
        assertFalse(TrustedProxyIpResolver.needsProxyHint("127.0.0.1", "198.51.100.7", "198.51.100.7", null))
        assertFalse(TrustedProxyIpResolver.needsProxyHint("127.0.0.1", "198.51.100.7", "198.51.100.7, 10.0.0.2", "198.51.100.7"))
        assertFalse(TrustedProxyIpResolver.needsProxyHint("127.0.0.1", "127.0.0.1", null, null))
        assertFalse(TrustedProxyIpResolver.needsProxyHint("203.0.113.9", "203.0.113.9", "1.1.1.1, 2.2.2.2", null))
    }
}
