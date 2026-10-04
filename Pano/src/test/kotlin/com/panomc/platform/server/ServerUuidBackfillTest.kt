package com.panomc.platform.server

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

class ServerUuidBackfillTest {
    @Test
    fun `assigns one uuid per id preserving order`() {
        val result = ServerUuidBackfill.assign(listOf(7L, 3L, 11L))

        assertEquals(listOf(7L, 3L, 11L), result.map { it.first })
    }

    @Test
    fun `generates parseable uuids`() {
        val result = ServerUuidBackfill.assign(listOf(1L, 2L))

        result.forEach { (_, uuid) ->
            assertEquals(uuid, UUID.fromString(uuid).toString())
        }
    }

    @Test
    fun `never repeats a uuid`() {
        val result = ServerUuidBackfill.assign((1L..500L).toList())

        assertEquals(500, result.map { it.second }.toSet().size)
    }

    @Test
    fun `retries when the generator repeats itself`() {
        val values = ArrayDeque(listOf("a", "a", "a", "b", "c"))

        val result = ServerUuidBackfill.assign(listOf(1L, 2L, 3L)) { values.removeFirst() }

        assertEquals(listOf("a", "b", "c"), result.map { it.second })
    }

    @Test
    fun `gives up on a generator that only ever returns one value`() {
        assertThrows(IllegalStateException::class.java) {
            ServerUuidBackfill.assign(listOf(1L, 2L)) { "same" }
        }
    }

    @Test
    fun `ignores duplicate ids`() {
        val result = ServerUuidBackfill.assign(listOf(4L, 4L, 5L))

        assertEquals(listOf(4L, 5L), result.map { it.first })
    }

    @Test
    fun `returns nothing for an empty table`() {
        assertTrue(ServerUuidBackfill.assign(emptyList()).isEmpty())
    }
}
