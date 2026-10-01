package com.panomc.platform.panel

import com.panomc.platform.server.PlatformCodeManager
import io.vertx.core.Vertx
import io.vertx.core.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

/** The platform key reaches the connect dialog as a pushed `platformKey` frame, not a poll. */
class PlatformKeyPushTest {
    @Test
    fun `the frame carries the key, when it started and how long it lives`() {
        val frame = JsonObject(PanelRealtimeHub.platformKeyFrame(123456, 1_000, 4_000))

        assertEquals("platformKey", frame.getString("type"))
        assertEquals(123456, frame.getInteger("key"))
        assertEquals(1_000L, frame.getLong("timeStarted"))
        assertEquals(30_000L, frame.getLong("periodMs"))
        assertEquals(4_000L, frame.getLong("serverTime"))
    }

    @Test
    fun `every rotation tells the listeners, after the new key is in place`() {
        val vertx = Vertx.vertx()

        try {
            val manager = PlatformCodeManager(vertx)
            val seen = mutableListOf<Pair<Int, Long>>()

            manager.addRotationListener { seen += manager.getPlatformKey() to manager.getTimeStarted() }
            // A failing listener must not keep the others from hearing about it.
            manager.addRotationListener { throw IllegalStateException("boom") }
            manager.addRotationListener { seen += -1 to 0L }

            val before = manager.getTimeStarted()

            manager.rotate()

            assertEquals(2, seen.size)
            assertEquals(manager.getPlatformKey(), seen[0].first)
            assertEquals(manager.getTimeStarted(), seen[0].second)
            assertNotEquals(0L, before)
            assertEquals(-1, seen[1].first)
        } finally {
            vertx.close()
        }
    }
}
