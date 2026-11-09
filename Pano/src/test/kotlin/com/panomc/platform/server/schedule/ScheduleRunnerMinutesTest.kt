package com.panomc.platform.server.schedule

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ScheduleRunnerMinutesTest {
    @Test
    fun `first tick checks only its own minute`() {
        assertEquals(listOf(100L), ScheduleRunner.minutesToCheck(null, 100))
    }

    @Test
    fun `a tick that lands two minutes on also checks the skipped minute`() {
        assertEquals(listOf(101L, 102L), ScheduleRunner.minutesToCheck(100, 102))
    }

    @Test
    fun `a minute already handled is not checked again`() {
        assertEquals(emptyList<Long>(), ScheduleRunner.minutesToCheck(100, 100))
        assertEquals(emptyList<Long>(), ScheduleRunner.minutesToCheck(100, 99))
    }

    @Test
    fun `a long gap catches up a few minutes only`() {
        assertEquals(listOf(197L, 198L, 199L, 200L), ScheduleRunner.minutesToCheck(100, 200))
    }
}
