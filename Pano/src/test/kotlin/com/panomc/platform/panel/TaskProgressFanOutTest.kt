package com.panomc.platform.panel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Who gets a server task's `taskProgress` frames, and how often (SM-68, §2.4.33). */
class TaskProgressFanOutTest {
    private fun listAudience(
        userId: Long = 2,
        subscribeNodes: Boolean = false,
        canManageServers: Boolean = true,
        subscribeServers: Boolean = false,
        subscribeServerId: Long? = null
    ) = PanelRealtimeHub.isTaskServerAudience(
        userId = userId,
        subscribeNodes = subscribeNodes,
        canManageServers = canManageServers,
        subscribeServers = subscribeServers,
        subscribeServerId = subscribeServerId,
        taskCreatedBy = 1,
        taskServerId = 10
    )

    @Test
    fun `the servers list and a page on that server get the frame`() {
        assertTrue(listAudience(subscribeServers = true))
        assertTrue(listAudience(subscribeServerId = 10))
    }

    @Test
    fun `other servers, missing permission and the owner audiences do not get it twice`() {
        assertFalse(listAudience(subscribeServerId = 11))
        assertFalse(listAudience())
        assertFalse(listAudience(subscribeServers = true, canManageServers = false))

        // Already on every frame through the owner audience.
        assertFalse(listAudience(userId = 1, subscribeServers = true))
        assertFalse(listAudience(subscribeNodes = true, subscribeServers = true))
        assertTrue(PanelRealtimeHub.isTaskOwnerAudience(1, false, 1))
        assertTrue(PanelRealtimeHub.isTaskOwnerAudience(2, true, 1))
        assertFalse(PanelRealtimeHub.isTaskOwnerAudience(2, false, 1))
    }

    @Test
    fun `frames are paced to two a second and the newest held one is flushed`() {
        val throttle = TaskFrameThrottle(500)

        assertEquals(TaskFrameThrottle.Offer.Send, throttle.offer("t", "f1", false, 1_000))
        assertEquals(TaskFrameThrottle.Offer.Hold(400), throttle.offer("t", "f2", false, 1_100))
        // Timer already armed: only the frame is replaced.
        assertEquals(TaskFrameThrottle.Offer.Hold(null), throttle.offer("t", "f3", false, 1_200))

        assertEquals("f3", throttle.flush("t", 1_500))
        assertNull(throttle.flush("t", 1_500))

        assertEquals(TaskFrameThrottle.Offer.Hold(300), throttle.offer("t", "f4", false, 1_700))
        assertEquals(TaskFrameThrottle.Offer.Send, throttle.offer("t", "f5", false, 2_000))

        // Other tasks are paced on their own.
        assertEquals(TaskFrameThrottle.Offer.Send, throttle.offer("u", "g1", false, 2_000))
    }

    @Test
    fun `an end state goes out at once and cancels what was held`() {
        val throttle = TaskFrameThrottle(500)

        throttle.offer("t", "f1", false, 1_000)
        throttle.offer("t", "f2", false, 1_100)

        assertEquals(TaskFrameThrottle.Offer.Send, throttle.offer("t", "done", true, 1_150))
        assertNull(throttle.flush("t", 1_500))
    }
}
