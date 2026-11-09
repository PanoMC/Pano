package com.panomc.platform.util

import com.panomc.platform.config.PanoConfig
import com.google.gson.Gson
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TrustPrivateProxiesTest {
    @Test
    fun `private peers are trusted by default, as before`() {
        assertTrue(TrustedProxyIpResolver.isTrustedPeer("172.17.0.1", emptyList(), trustPrivate = true))
        assertEquals(
            "203.0.113.7",
            TrustedProxyIpResolver.resolveClientIp("172.17.0.1", "203.0.113.7", null, emptyList(), trustPrivate = true)
        )
    }

    @Test
    fun `switched off, an unlisted private peer cannot set the client address`() {
        assertFalse(TrustedProxyIpResolver.isTrustedPeer("172.17.0.1", emptyList(), trustPrivate = false))
        assertFalse(TrustedProxyIpResolver.isTrustedPeer("127.0.0.1", emptyList(), trustPrivate = false))
        assertEquals(
            "172.17.0.1",
            TrustedProxyIpResolver.resolveClientIp("172.17.0.1", "203.0.113.7", "198.51.100.1", emptyList(), trustPrivate = false)
        )
    }

    @Test
    fun `switched off, a listed proxy is still believed`() {
        val listed = listOf("172.17.0.0/16")

        assertTrue(TrustedProxyIpResolver.isTrustedPeer("172.17.0.1", listed, trustPrivate = false))
        assertEquals(
            "203.0.113.7",
            TrustedProxyIpResolver.resolveClientIp("172.17.0.1", "203.0.113.7", null, listed, trustPrivate = false)
        )
        assertNotEquals(
            "203.0.113.7",
            TrustedProxyIpResolver.resolveClientIp("10.0.0.5", "203.0.113.7", null, listed, trustPrivate = false)
        )
    }

    @Test
    fun `a server block without the key keeps trusting private peers`() {
        val server = Gson().fromJson("{\"host\":\"0.0.0.0\"}", PanoConfig.Companion.ServerConfig::class.java)

        assertTrue(server.trustPrivateProxies != false)
    }
}
