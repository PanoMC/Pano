package com.panomc.platform.server.schedule

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ScheduleWarningsTest {
    @Test
    fun `adds the standard offsets below the configured lead time`() {
        assertEquals(listOf(15, 5, 1), ScheduleWarnings.offsetsFor(15))
        assertEquals(listOf(10, 5, 1), ScheduleWarnings.offsetsFor(10))
    }

    @Test
    fun `never announces the same minute twice`() {
        assertEquals(listOf(5, 1), ScheduleWarnings.offsetsFor(5))
        assertEquals(listOf(1), ScheduleWarnings.offsetsFor(1))
    }

    @Test
    fun `never announces a minute past the lead time`() {
        assertEquals(listOf(3, 1), ScheduleWarnings.offsetsFor(3))
        assertTrue(ScheduleWarnings.offsetsFor(2).all { it <= 2 })
    }

    @Test
    fun `zero means no countdown at all`() {
        assertTrue(ScheduleWarnings.offsetsFor(0).isEmpty())
        assertTrue(ScheduleWarnings.offsetsFor(-5).isEmpty())
    }

    @Test
    fun `caps an absurd lead time`() {
        assertEquals(ScheduleWarnings.MAX_WARN_MINUTES, ScheduleWarnings.offsetsFor(10_000).first())
    }

    @Test
    fun `says what is about to happen, in the right number`() {
        assertEquals("say Server stops in 5 minutes", ScheduleWarnings.message(5, restarting = false))
        assertEquals("say Server restarts in 1 minute", ScheduleWarnings.message(1, restarting = true))
    }
}
