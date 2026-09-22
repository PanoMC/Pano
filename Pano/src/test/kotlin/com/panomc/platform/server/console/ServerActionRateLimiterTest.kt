package com.panomc.platform.server.console

import com.panomc.platform.server.console.ServerActionRateLimiter.Action
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ServerActionRateLimiterTest {

    @Test
    fun `allows up to the limit inside one window`() {
        val limiter = ServerActionRateLimiter()

        repeat(Action.POWER.limit) {
            assertTrue(limiter.tryAcquire(Action.POWER, userId = 1, serverId = 1, now = 1_000))
        }
    }

    @Test
    fun `refuses the one past the limit`() {
        val limiter = ServerActionRateLimiter()

        repeat(Action.POWER.limit) {
            limiter.tryAcquire(Action.POWER, userId = 1, serverId = 1, now = 1_000)
        }

        assertFalse(limiter.tryAcquire(Action.POWER, userId = 1, serverId = 1, now = 1_000))
    }

    @Test
    fun `lets the window slide`() {
        val limiter = ServerActionRateLimiter()

        repeat(Action.POWER.limit) {
            limiter.tryAcquire(Action.POWER, userId = 1, serverId = 1, now = 1_000)
        }

        assertFalse(limiter.tryAcquire(Action.POWER, userId = 1, serverId = 1, now = 1_000 + Action.POWER.windowMs - 1))
        assertTrue(limiter.tryAcquire(Action.POWER, userId = 1, serverId = 1, now = 1_000 + Action.POWER.windowMs + 1))
    }

    @Test
    fun `one action never spends another action's budget`() {
        val limiter = ServerActionRateLimiter()

        repeat(Action.BACKUP.limit) {
            limiter.tryAcquire(Action.BACKUP, userId = 1, serverId = 1, now = 1_000)
        }

        assertFalse(limiter.tryAcquire(Action.BACKUP, userId = 1, serverId = 1, now = 1_000))
        assertTrue(limiter.tryAcquire(Action.POWER, userId = 1, serverId = 1, now = 1_000))
    }

    @Test
    fun `one user never blocks another`() {
        val limiter = ServerActionRateLimiter()

        repeat(Action.POWER.limit) {
            limiter.tryAcquire(Action.POWER, userId = 1, serverId = 1, now = 1_000)
        }

        assertTrue(limiter.tryAcquire(Action.POWER, userId = 2, serverId = 1, now = 1_000))
    }

    @Test
    fun `one server never blocks another`() {
        val limiter = ServerActionRateLimiter()

        repeat(Action.POWER.limit) {
            limiter.tryAcquire(Action.POWER, userId = 1, serverId = 1, now = 1_000)
        }

        assertTrue(limiter.tryAcquire(Action.POWER, userId = 1, serverId = 2, now = 1_000))
    }

    @Test
    fun `resetting a pair forgets every action of it`() {
        val limiter = ServerActionRateLimiter()

        repeat(Action.POWER.limit) {
            limiter.tryAcquire(Action.POWER, userId = 1, serverId = 1, now = 1_000)
        }

        limiter.reset(userId = 1, serverId = 1)

        assertTrue(limiter.tryAcquire(Action.POWER, userId = 1, serverId = 1, now = 1_000))
    }

    @Test
    fun `resetting a server forgets every user of it`() {
        val limiter = ServerActionRateLimiter()

        repeat(Action.POWER.limit) {
            limiter.tryAcquire(Action.POWER, userId = 1, serverId = 7, now = 1_000)
        }

        limiter.resetServer(7)

        assertTrue(limiter.tryAcquire(Action.POWER, userId = 1, serverId = 7, now = 1_000))
    }

    @Test
    fun `the console limit is the one the console contract pinned`() {
        assertEquals(10, Action.CONSOLE_COMMAND.limit)
        assertEquals(10_000L, Action.CONSOLE_COMMAND.windowMs)
    }

    @Test
    fun `every action carries the limits AGENT section 2-4-12 fixed`() {
        assertEquals(6 to 60_000L, Action.POWER.limit to Action.POWER.windowMs)
        assertEquals(60 to 60_000L, Action.FILE_WRITE.limit to Action.FILE_WRITE.windowMs)
        assertEquals(10 to 60_000L, Action.UPLOAD.limit to Action.UPLOAD.windowMs)
        assertEquals(3 to 600_000L, Action.BACKUP.limit to Action.BACKUP.windowMs)
        assertEquals(10 to 600_000L, Action.PLUGIN_INSTALL.limit to Action.PLUGIN_INSTALL.windowMs)
    }

    @Test
    fun `Java runtime actions are limited per user across every node`() {
        val limiter = ServerActionRateLimiter()

        repeat(Action.JAVA_RUNTIME.limit) {
            assertTrue(limiter.tryAcquireForUser(Action.JAVA_RUNTIME, 1, now = 1_000))
        }

        assertFalse(limiter.tryAcquireForUser(Action.JAVA_RUNTIME, 1, now = 1_000))

        // Another user has a window of their own, and the window moves on.
        assertTrue(limiter.tryAcquireForUser(Action.JAVA_RUNTIME, 2, now = 1_000))
        assertTrue(limiter.tryAcquireForUser(Action.JAVA_RUNTIME, 1, now = 1_000 + Action.JAVA_RUNTIME.windowMs))
    }
}
