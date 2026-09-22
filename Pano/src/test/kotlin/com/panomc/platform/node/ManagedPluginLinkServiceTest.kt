package com.panomc.platform.node

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ManagedPluginLinkServiceTest {
    @Test
    fun `reads host, port and scheme out of a website url`() {
        val address = ManagedPluginLinkService.parse("https://panomc.com")!!

        assertEquals("panomc.com", address.host)
        assertEquals(443, address.port)
        assertTrue(address.ssl)
    }

    @Test
    fun `keeps an explicit port`() {
        val address = ManagedPluginLinkService.parse("http://127.0.0.1:8088/")!!

        assertEquals("127.0.0.1", address.host)
        assertEquals(8088, address.port)
        assertFalse(address.ssl)
    }

    @Test
    fun `defaults a plain http url to eighty`() {
        val address = ManagedPluginLinkService.parse("http://example.test")!!

        assertEquals(80, address.port)
        assertFalse(address.ssl)
    }

    @Test
    fun `treats a bare host as http`() {
        val address = ManagedPluginLinkService.parse("example.test:9000")!!

        assertEquals("example.test", address.host)
        assertEquals(9000, address.port)
        assertFalse(address.ssl)
    }

    @Test
    fun `refuses what it cannot turn into a host`() {
        assertNull(ManagedPluginLinkService.parse(null))
        assertNull(ManagedPluginLinkService.parse(""))
        assertNull(ManagedPluginLinkService.parse("   "))
        assertNull(ManagedPluginLinkService.parse("http://"))
    }
}
