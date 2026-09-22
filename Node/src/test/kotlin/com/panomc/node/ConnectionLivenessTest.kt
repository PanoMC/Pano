package com.panomc.node

import com.panomc.node.net.ConnectionLiveness
import com.panomc.node.net.ConnectionLiveness.Verdict
import com.panomc.node.net.PlatformConnection
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ConnectionLivenessTest {
    private var now = 1_000L

    private val liveness = ConnectionLiveness(TIMEOUT) { now }

    @Test
    fun `a socket that just opened is pinged, not given up`() {
        assertEquals(Verdict.PING, liveness.check())
        assertEquals(0L, liveness.silenceMillis())
    }

    @Test
    fun `keeps a silent socket right up to the timeout`() {
        now += TIMEOUT - 1

        assertEquals(Verdict.PING, liveness.check())
    }

    @Test
    fun `gives a socket up once the timeout passes with nothing heard`() {
        now += TIMEOUT

        assertEquals(Verdict.GIVE_UP, liveness.check())
        assertEquals(TIMEOUT, liveness.silenceMillis())
    }

    @Test
    fun `anything heard starts the timeout over`() {
        now += TIMEOUT - 1

        liveness.heard()

        now += TIMEOUT - 1

        assertEquals(Verdict.PING, liveness.check())

        now += 1

        assertEquals(Verdict.GIVE_UP, liveness.check())
    }

    @Test
    fun `a link whose pings are answered is never given up`() {
        repeat(1_000) {
            now += INTERVAL

            assertEquals(Verdict.PING, liveness.check())

            // The pong, one round trip later.
            now += 250

            liveness.heard()
        }
    }

    @Test
    fun `at the default cadence a dead link is given up on the third tick`() {
        val defaults = ConnectionLiveness(PlatformConnection.LIVENESS_TIMEOUT_MILLIS) { now }

        val verdicts = (1..3).map {
            now += PlatformConnection.PING_INTERVAL_MILLIS

            defaults.check()
        }

        assertEquals(listOf(Verdict.PING, Verdict.PING, Verdict.GIVE_UP), verdicts)
    }

    @Test
    fun `the defaults leave a slow link to Pano to judge first`() {
        // Pano's default heartbeat timeout (mc-server-connection.heartbeat-timeout-seconds).
        assertTrue(PlatformConnection.LIVENESS_TIMEOUT_MILLIS >= 75_000L)
        assertTrue(PlatformConnection.LIVENESS_TIMEOUT_MILLIS >= PlatformConnection.PING_INTERVAL_MILLIS * 3)
    }

    companion object {
        private const val INTERVAL = 30_000L
        private const val TIMEOUT = 90_000L
    }
}
