package com.panomc.platform.server.schedule

import com.panomc.platform.server.feature.ServerFeatureSource
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Only one side runs a server's schedules: a plugin that is not the runner is emptied on every sync. */
class ScheduleRunnerHandoverTest {
    @Test
    fun `a node taking the schedules back empties the plugin's copy`() {
        assertTrue(ServerScheduleService.clearsPlugin(ServerFeatureSource.NODE, pluginConnected = true, pluginRunsSchedules = true))
        assertTrue(ServerScheduleService.clearsPlugin(ServerFeatureSource.PANO, pluginConnected = true, pluginRunsSchedules = true))
        assertTrue(ServerScheduleService.clearsPlugin(null, pluginConnected = true, pluginRunsSchedules = true))
    }

    @Test
    fun `the plugin keeps the schedules it runs, and nothing is sent where nobody listens`() {
        assertFalse(ServerScheduleService.clearsPlugin(ServerFeatureSource.PLUGIN, pluginConnected = true, pluginRunsSchedules = true))
        assertFalse(ServerScheduleService.clearsPlugin(ServerFeatureSource.NODE, pluginConnected = false, pluginRunsSchedules = true))
        assertFalse(ServerScheduleService.clearsPlugin(ServerFeatureSource.NODE, pluginConnected = true, pluginRunsSchedules = false))
    }
}
