package com.panomc.platform.server

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class TaskTransferTest {
    @Test
    fun `a frame's download becomes a transfer`() {
        assertEquals(TaskTransfer(100, 1000, 25), TaskTransfer.of(100, 1000, 25))
    }

    @Test
    fun `a size or rate that makes no sense is left out, a frame without bytes has no transfer`() {
        assertEquals(TaskTransfer(100, null, null), TaskTransfer.of(100, -1, -5))
        assertEquals(TaskTransfer(100, null, 3), TaskTransfer.of(100, 0, 3))
        assertNull(TaskTransfer.of(null, 1000, 25))
        assertNull(TaskTransfer.of(-1, 1000, 25))
    }

    @Test
    fun `the JSON carries the three figures`() {
        val json = TaskTransfer(100, null, 7).toJsonObject()

        assertEquals(100L, json.getLong("done"))
        assertNull(json.getLong("total"))
        assertEquals(7L, json.getLong("bytesPerSecond"))
    }
}
