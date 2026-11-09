package com.panomc.platform.server.event

import java.util.concurrent.ConcurrentHashMap

/**
 * Caps wrong password checks that a connected server can make, so a server cannot be used to try
 * passwords against website accounts without end.
 *
 * Failures are counted per server and username, and per server in total, inside a fixed window.
 * Once a limit is reached, checks are refused (treated as wrong) until the window ends. A correct
 * password clears the username's counter.
 */
class ServerAuthAttemptLimiter(
    private val maxFailuresPerUser: Int = 10,
    private val maxFailuresPerServer: Int = 200,
    private val windowMs: Long = 10 * 60 * 1000L,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private class Window(var start: Long, var failures: Int)

    private val perUser = ConcurrentHashMap<String, Window>()
    private val perServer = ConcurrentHashMap<Long, Window>()

    private fun userKey(serverId: Long, username: String) = "$serverId:${username.lowercase()}"

    private fun current(window: Window?, now: Long): Int =
        if (window == null || now - window.start >= windowMs) 0 else window.failures

    fun isBlocked(serverId: Long, username: String): Boolean {
        val now = clock()
        prune(now)
        return current(perUser[userKey(serverId, username)], now) >= maxFailuresPerUser ||
                current(perServer[serverId], now) >= maxFailuresPerServer
    }

    fun recordFailure(serverId: Long, username: String) {
        val now = clock()
        bump(perUser.computeIfAbsent(userKey(serverId, username)) { Window(now, 0) }, now)
        bump(perServer.computeIfAbsent(serverId) { Window(now, 0) }, now)
    }

    fun recordSuccess(serverId: Long, username: String) {
        perUser.remove(userKey(serverId, username))
    }

    private fun bump(window: Window, now: Long) {
        synchronized(window) {
            if (now - window.start >= windowMs) {
                window.start = now
                window.failures = 0
            }
            window.failures++
        }
    }

    private fun prune(now: Long) {
        if (perUser.size < 10_000) return
        perUser.entries.removeIf { now - it.value.start >= windowMs }
        perServer.entries.removeIf { now - it.value.start >= windowMs }
    }
}
