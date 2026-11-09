package com.panomc.platform.db

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class DatabaseAddressTest {
    @Test
    fun `host and host with port`() {
        assertEquals(DatabaseAddress("127.0.0.1", 3306), DatabaseAddress.parse("127.0.0.1"))
        assertEquals(DatabaseAddress("db.example", 3307), DatabaseAddress.parse("db.example:3307"))
    }

    @Test
    fun `IPv6 literals`() {
        assertEquals(DatabaseAddress("::1", 3306), DatabaseAddress.parse("::1"))
        assertEquals(DatabaseAddress("fd00::5", 3306), DatabaseAddress.parse("fd00::5"))
        assertEquals(DatabaseAddress("::1", 3306), DatabaseAddress.parse("[::1]"))
        assertEquals(DatabaseAddress("::1", 3310), DatabaseAddress.parse("[::1]:3310"))
    }

    @Test
    fun `a bad port is refused instead of throwing`() {
        assertNull(DatabaseAddress.parse("localhost:"))
        assertNull(DatabaseAddress.parse("localhost:abc"))
        assertNull(DatabaseAddress.parse("localhost:70000"))
        assertNull(DatabaseAddress.parse("[::1"))
        assertNull(DatabaseAddress.parse("[::1]x"))
    }
}
