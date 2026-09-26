package com.panomc.platform.setup

import com.panomc.platform.config.PanoConfig
import com.panomc.platform.setup.SetupManager.Companion.emailView
import com.panomc.platform.setup.SetupManager.Companion.skippedSteps
import com.panomc.platform.setup.SetupManager.Companion.stepBackTo
import com.panomc.platform.setup.SetupManager.Companion.stepForward
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class SetupStepsTest {
    @Test
    fun `nothing managed walks every step`() {
        val none = skippedSteps(false, false)

        assertEquals(listOf(1, 2, 3, 4, 4), listOf(0, 1, 2, 3, 4).map { stepForward(it, none) })
        assertEquals(listOf(0, 0, 1, 2, 3), listOf(-1, 0, 1, 2, 3).map { stepBackTo(it, none) })
    }

    @Test
    fun `managed database skips step 2`() {
        val db = skippedSteps(true, false)

        assertEquals(3, stepForward(1, db))
        assertEquals(1, stepBackTo(2, db))
        assertEquals(3, stepBackTo(3, db))
    }

    @Test
    fun `managed database and mail go from 1 straight to 4 and back to 1`() {
        val both = skippedSteps(true, true)

        assertEquals(4, stepForward(1, both))
        assertEquals(1, stepBackTo(3, both))
        assertEquals(1, stepBackTo(2, both))
    }

    @Test
    fun `managed mail alone skips step 3 both ways`() {
        val mail = skippedSteps(false, true)

        assertEquals(4, stepForward(2, mail))
        assertEquals(2, stepBackTo(3, mail))
    }

    @Test
    fun `step 3 data never carries the smtp password`() {
        val mail = PanoConfig.Companion.EmailConfig(enabled = true, hostname = "relay.portal", username = "wl_abc", password = "relay-s3cret-value")

        val view = emailView(mail)

        assertEquals("relay.portal", view["hostname"])
        assertEquals("", view["password"])
        assertFalse(view.toString().contains("relay-s3cret-value"))
    }
}
