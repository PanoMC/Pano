package com.panomc.platform.server

import com.panomc.platform.server.event.ServerAuthAttemptLimiter
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ServerAuthAttemptLimiterTest {
    private var now = 1_000_000L
    private val limiter = ServerAuthAttemptLimiter(
        maxFailuresPerUser = 3,
        maxFailuresPerServer = 5,
        windowMs = 1_000,
        clock = { now }
    )

    @Test
    fun `blocks a username after too many wrong passwords until the window ends`() {
        repeat(3) { limiter.recordFailure(1, "Steve") }
        assertTrue(limiter.isBlocked(1, "steve"))
        assertFalse(limiter.isBlocked(2, "steve"))
        assertFalse(limiter.isBlocked(1, "alex"))
        now += 1_000
        assertFalse(limiter.isBlocked(1, "steve"))
    }

    @Test
    fun `blocks a server spraying many usernames`() {
        repeat(5) { limiter.recordFailure(1, "user$it") }
        assertTrue(limiter.isBlocked(1, "someone-else"))
        assertFalse(limiter.isBlocked(2, "someone-else"))
    }

    @Test
    fun `a correct password clears the username counter`() {
        repeat(2) { limiter.recordFailure(1, "steve") }
        limiter.recordSuccess(1, "steve")
        repeat(2) { limiter.recordFailure(1, "steve") }
        assertFalse(limiter.isBlocked(1, "steve"))
    }
}
