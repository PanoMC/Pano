package com.panomc.platform.route.api.setup.step

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ParseDatabaseHostTest {
    @Test
    fun `host with and without port`() {
        assertEquals("db" to 3306, parseDatabaseHost("db"))
        assertEquals("db" to 3307, parseDatabaseHost("db:3307"))
        assertEquals("127.0.0.1" to 3306, parseDatabaseHost(" 127.0.0.1 "))
        assertEquals("::1" to 3306, parseDatabaseHost("[::1]"))
        assertEquals("::1" to 3310, parseDatabaseHost("[::1]:3310"))
        assertEquals("fe80::1" to 3306, parseDatabaseHost("fe80::1"))
    }

    @Test
    fun `bad input is refused instead of crashing`() {
        listOf("", "db:abc", "db:0", "db:70000", ":3306", "[::1", "[::1]x", "[]:3306", null)
            .forEach { assertNull(parseDatabaseHost(it), it.toString()) }
    }
}
