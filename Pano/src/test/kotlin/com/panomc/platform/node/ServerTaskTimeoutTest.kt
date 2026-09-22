package com.panomc.platform.node

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ServerTaskTimeoutTest {
    private val now = 1_800_000_000_000L

    @Test
    fun `a task nobody acknowledged is given two minutes`() {
        assertEquals(ServerTaskTimeout.PENDING_TIMEOUT_MS, ServerTaskTimeout.timeoutMsFor(ServerTaskStatus.PENDING))

        assertFalse(ServerTaskTimeout.hasTimedOut(ServerTaskStatus.PENDING, now - 119_000, now))
        assertTrue(ServerTaskTimeout.hasTimedOut(ServerTaskStatus.PENDING, now - 120_000, now))
    }

    @Test
    fun `a task that is running is given ten minutes between frames`() {
        assertEquals(ServerTaskTimeout.RUNNING_TIMEOUT_MS, ServerTaskTimeout.timeoutMsFor(ServerTaskStatus.RUNNING))

        // A long download or a 200 MB backup can go minutes without a frame and must survive it.
        assertFalse(ServerTaskTimeout.hasTimedOut(ServerTaskStatus.RUNNING, now - 5 * 60_000, now))
        assertFalse(ServerTaskTimeout.hasTimedOut(ServerTaskStatus.RUNNING, now - 599_000, now))
        assertTrue(ServerTaskTimeout.hasTimedOut(ServerTaskStatus.RUNNING, now - 600_000, now))
    }

    @Test
    fun `the running deadline is longer than the pending one`() {
        assertTrue(ServerTaskTimeout.RUNNING_TIMEOUT_MS > ServerTaskTimeout.PENDING_TIMEOUT_MS)

        // The same silence means "no node took this" at PENDING and "still working" at RUNNING.
        val silence = now - 5 * 60_000

        assertTrue(ServerTaskTimeout.hasTimedOut(ServerTaskStatus.PENDING, silence, now))
        assertFalse(ServerTaskTimeout.hasTimedOut(ServerTaskStatus.RUNNING, silence, now))
    }

    @Test
    fun `a task that already ended is never timed out`() {
        assertNull(ServerTaskTimeout.timeoutMsFor(ServerTaskStatus.DONE))
        assertNull(ServerTaskTimeout.timeoutMsFor(ServerTaskStatus.FAILED))

        val ancient = now - 30L * 24 * 60 * 60 * 1000

        assertFalse(ServerTaskTimeout.hasTimedOut(ServerTaskStatus.DONE, ancient, now))
        assertFalse(ServerTaskTimeout.hasTimedOut(ServerTaskStatus.FAILED, ancient, now))
    }

    @Test
    fun `a clock that moved backwards never fails a healthy task`() {
        // An NTP correction or a resumed machine can make the last update look like the future.
        assertFalse(ServerTaskTimeout.hasTimedOut(ServerTaskStatus.PENDING, now + 60_000, now))
        assertFalse(ServerTaskTimeout.hasTimedOut(ServerTaskStatus.RUNNING, now + 60 * 60_000, now))
    }

    @Test
    fun `the error written on a timeout is the one the panel reads`() {
        assertEquals("TIMEOUT", ServerTaskTimeout.TIMEOUT_ERROR)
    }
}
