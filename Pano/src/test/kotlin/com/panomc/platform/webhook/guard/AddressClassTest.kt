package com.panomc.platform.webhook.guard

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.InetAddress

class AddressClassTest {
    private fun cls(text: String) = AddressClass.of(InetAddress.getByName(text))

    @Test
    fun `ipv4 ranges of 11 section 7_2`() {
        val expected = mapOf(
            "0.1.2.3" to AddressClass.UNSPECIFIED, "10.0.0.1" to AddressClass.PRIVATE, "10.255.255.255" to AddressClass.PRIVATE,
            "100.64.0.1" to AddressClass.PRIVATE, "100.127.255.255" to AddressClass.PRIVATE, "100.128.0.1" to AddressClass.PUBLIC,
            "100.63.255.255" to AddressClass.PUBLIC, "100.100.100.200" to AddressClass.METADATA,
            "127.0.0.1" to AddressClass.LOOPBACK, "127.255.255.254" to AddressClass.LOOPBACK,
            "169.254.169.254" to AddressClass.LINK_LOCAL, "169.255.0.1" to AddressClass.PUBLIC,
            "172.15.255.255" to AddressClass.PUBLIC, "172.16.0.1" to AddressClass.PRIVATE, "172.31.255.255" to AddressClass.PRIVATE,
            "172.32.0.1" to AddressClass.PUBLIC, "192.168.1.1" to AddressClass.PRIVATE, "192.0.0.1" to AddressClass.RESERVED,
            "192.0.0.192" to AddressClass.METADATA, "192.0.2.7" to AddressClass.RESERVED, "198.18.0.1" to AddressClass.RESERVED,
            "198.19.255.255" to AddressClass.RESERVED, "198.20.0.1" to AddressClass.PUBLIC, "198.51.100.9" to AddressClass.RESERVED,
            "203.0.113.9" to AddressClass.RESERVED, "224.0.0.1" to AddressClass.MULTICAST, "239.255.255.255" to AddressClass.MULTICAST,
            "240.0.0.1" to AddressClass.BROADCAST, "255.255.255.255" to AddressClass.BROADCAST, "168.63.129.16" to AddressClass.METADATA,
            "8.8.8.8" to AddressClass.PUBLIC, "93.184.216.34" to AddressClass.PUBLIC
        )
        for ((text, c) in expected) assertEquals(c, cls(text), text)
    }

    @Test
    fun `ipv6 ranges`() {
        val expected = mapOf(
            "::" to AddressClass.UNSPECIFIED, "::1" to AddressClass.LOOPBACK, "fe80::1" to AddressClass.LINK_LOCAL,
            "febf::1" to AddressClass.LINK_LOCAL, "fc00::1" to AddressClass.PRIVATE, "fd12:3456::1" to AddressClass.PRIVATE,
            "fd00:ec2::254" to AddressClass.LINK_LOCAL, "ff02::1" to AddressClass.MULTICAST, "2001:db8::1" to AddressClass.RESERVED,
            "2606:4700::1111" to AddressClass.PUBLIC, "fec0::1" to AddressClass.PRIVATE
        )
        for ((text, c) in expected) assertEquals(c, cls(text), text)
    }

    @Test
    fun `mapped, nat64 and 6to4 forms are classified by the embedded ipv4 address`() {
        fun bytes(vararg v: Int) = ByteArray(16) { v.getOrElse(it) { 0 }.toByte() }
        // ::ffff:10.0.0.1 and ::ffff:169.254.169.254 as raw 16 byte arrays (Java would fold them to Inet4Address)
        assertEquals(AddressClass.PRIVATE, AddressClass.of(bytes(*IntArray(10) { 0 }, 0xff, 0xff, 10, 0, 0, 1)))
        assertEquals(AddressClass.LINK_LOCAL, AddressClass.of(bytes(*IntArray(10) { 0 }, 0xff, 0xff, 169, 254, 169, 254)))
        assertEquals(AddressClass.PUBLIC, AddressClass.of(bytes(*IntArray(10) { 0 }, 0xff, 0xff, 8, 8, 8, 8)))
        // 64:ff9b::127.0.0.1
        assertEquals(AddressClass.LOOPBACK, AddressClass.of(bytes(0x00, 0x64, 0xff, 0x9b, 0, 0, 0, 0, 0, 0, 0, 0, 127, 0, 0, 1)))
        assertEquals(AddressClass.PUBLIC, AddressClass.of(bytes(0x00, 0x64, 0xff, 0x9b, 0, 0, 0, 0, 0, 0, 0, 0, 8, 8, 8, 8)))
        // 2002:0a00:0001::  = 6to4 of 10.0.0.1
        assertEquals(AddressClass.PRIVATE, AddressClass.of(bytes(0x20, 0x02, 10, 0, 0, 1)))
        assertEquals(AddressClass.LINK_LOCAL, AddressClass.of(bytes(0x20, 0x02, 169, 254, 169, 254)))
        // deprecated IPv4-compatible ::127.0.0.1 style
        assertEquals(AddressClass.LOOPBACK, AddressClass.of(bytes(*IntArray(12) { 0 }, 127, 0, 0, 1)))
    }

    @Test
    fun `which classes the private flag opens`() {
        for (c in listOf(AddressClass.LOOPBACK, AddressClass.PRIVATE, AddressClass.RESERVED)) {
            assertFalse(c.isAllowed(false), "$c")
            assertTrue(c.isAllowed(true), "$c")
        }
        for (c in listOf(AddressClass.LINK_LOCAL, AddressClass.METADATA, AddressClass.UNSPECIFIED, AddressClass.BROADCAST, AddressClass.MULTICAST)) {
            assertFalse(c.isAllowed(false), "$c")
            assertFalse(c.isAllowed(true), "$c")
        }
        assertTrue(AddressClass.PUBLIC.isAllowed(false))
        assertTrue(AddressClass.PUBLIC.isAllowed(true))
    }
}
