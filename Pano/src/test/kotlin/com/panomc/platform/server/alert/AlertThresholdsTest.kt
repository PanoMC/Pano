package com.panomc.platform.server.alert

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.roundToInt

class AlertThresholdsTest {
    @Test
    fun `reports a disk over the threshold and not one exactly on it`() {
        assertTrue(AlertThresholds.isDiskLow(95, 100))
        assertFalse(AlertThresholds.isDiskLow(90, 100))
        assertFalse(AlertThresholds.isDiskLow(10, 100))
    }

    @Test
    fun `a disk it could not measure is not a full disk`() {
        assertFalse(AlertThresholds.isDiskLow(0, 0))
        assertFalse(AlertThresholds.isDiskLow(100, 0))
        assertFalse(AlertThresholds.isDiskLow(-1, 100))
        assertEquals(0.0, AlertThresholds.diskRatio(50, 0))
    }

    @Test
    fun `reports the share in use as a percentage the panel can show`() {
        assertEquals(95, (AlertThresholds.diskRatio(95, 100) * 100).roundToInt())
        assertEquals(100, (AlertThresholds.diskRatio(200, 100) * 100).roundToInt())
    }

    @Test
    fun `needs a run of bad samples before it says anything`() {
        val window = TpsLowWindow()

        repeat(AlertThresholds.TPS_SAMPLES - 1) {
            assertFalse(window.offer(1, 10.0))
        }

        assertTrue(window.offer(1, 10.0))
    }

    @Test
    fun `says it only once per episode`() {
        val window = TpsLowWindow()

        repeat(AlertThresholds.TPS_SAMPLES) { window.offer(1, 10.0) }

        assertFalse(window.offer(1, 10.0))
        assertFalse(window.offer(1, 9.0))
    }

    @Test
    fun `one good sample resets the run`() {
        val window = TpsLowWindow()

        repeat(AlertThresholds.TPS_SAMPLES - 1) { window.offer(1, 10.0) }

        assertFalse(window.offer(1, 20.0))
        assertEquals(0, window.countFor(1))

        repeat(AlertThresholds.TPS_SAMPLES - 1) { assertFalse(window.offer(1, 10.0)) }

        assertTrue(window.offer(1, 10.0))
    }

    @Test
    fun `a server exactly on the floor is not below it`() {
        val window = TpsLowWindow()

        repeat(AlertThresholds.TPS_SAMPLES * 2) {
            assertFalse(window.offer(1, AlertThresholds.TPS_FLOOR))
        }
    }

    @Test
    fun `a proxy that reports no tick rate never counts either way`() {
        val window = TpsLowWindow()

        repeat(AlertThresholds.TPS_SAMPLES * 2) {
            assertFalse(window.offer(1, null))
        }

        assertEquals(0, window.countFor(1))
    }

    @Test
    fun `counts each server on its own`() {
        val window = TpsLowWindow()

        repeat(AlertThresholds.TPS_SAMPLES - 1) {
            window.offer(1, 10.0)
            window.offer(2, 10.0)
        }

        assertTrue(window.offer(1, 10.0))
        assertTrue(window.offer(2, 10.0))

        window.forget(1)

        assertEquals(0, window.countFor(1))
        assertEquals(AlertThresholds.TPS_SAMPLES, window.countFor(2))
    }
}
