package com.panomc.platform.server.console

import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.context.annotation.Lazy
import org.springframework.context.annotation.Scope
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap

/**
 * Sliding-window limiter for everything the panel can do to one server, per user and per server.
 *
 * Generalised out of the console-only limiter it started as (§2.4.12). Every action here is cheap
 * for the panel and expensive somewhere else: a power action stops a server full of players, a
 * file write crosses the node socket and hits a disk, a backup copies a world, a plugin install
 * downloads from a third party. A stuck key, a runaway script or a hostile session must not be
 * able to turn any of them into a flood, and the limit belongs on Pano's side because the node
 * would otherwise be the thing absorbing it.
 *
 * The window is per (action, user, server) on purpose: one admin hammering one server's files
 * never blocks another admin, the same admin on another server, or that admin's console.
 *
 * In-memory and therefore per Pano process, like the rest of the platform's rate limiting.
 */
@Lazy
@Component
@Scope(value = ConfigurableBeanFactory.SCOPE_SINGLETON)
class ServerActionRateLimiter {
    /**
     * What may be limited, and how much of it is allowed.
     *
     * The numbers are §2.4.12's. They are generous enough that a person clicking buttons never
     * sees one and tight enough that a loop does immediately.
     */
    enum class Action(val limit: Int, val windowMs: Long) {
        /** Console commands: the original limit, kept exactly as it was. */
        CONSOLE_COMMAND(10, 10_000L),
        POWER(6, 60_000L),
        FILE_WRITE(60, 60_000L),
        UPLOAD(10, 60_000L),
        BACKUP(3, 10L * 60_000L),
        PLUGIN_INSTALL(10, 10L * 60_000L),

        /**
         * Java runtime installs and removals on nodes (SM-63, §2.4.28). Per user across every
         * node, see [tryAcquireForUser]: each one is a ~50 MB download from Adoptium or Azul, and
         * it is the user's traffic being limited, not one node's.
         */
        JAVA_RUNTIME(10, 10L * 60_000L)
    }

    private data class Key(val action: Action, val userId: Long, val serverId: Long)

    private val hits = ConcurrentHashMap<Key, ArrayDeque<Long>>()

    @Volatile
    private var lastCleanupAt = 0L

    /**
     * Records one [action] for [userId] on [serverId] and reports whether it is allowed.
     *
     * [now] is injectable so the behaviour around the window edge can be tested without sleeping.
     */
    fun tryAcquire(
        action: Action,
        userId: Long,
        serverId: Long,
        now: Long = System.currentTimeMillis()
    ): Boolean {
        cleanUp(now)

        val timestamps = hits.computeIfAbsent(Key(action, userId, serverId)) { ArrayDeque() }

        synchronized(timestamps) {
            val windowStart = now - action.windowMs

            while (timestamps.isNotEmpty() && timestamps.first() <= windowStart) {
                timestamps.removeFirst()
            }

            if (timestamps.size >= action.limit) {
                return false
            }

            timestamps.addLast(now)

            return true
        }
    }

    /**
     * [tryAcquire] for an action that is not about one server, counted per user across everything.
     *
     * Shares the map under a server id no row can have, rather than getting a second map with its
     * own cleanup.
     */
    fun tryAcquireForUser(
        action: Action,
        userId: Long,
        now: Long = System.currentTimeMillis()
    ): Boolean = tryAcquire(action, userId, USER_WIDE_SERVER_ID, now)

    /** Forgets everything recorded for [userId] on [serverId]. Used by tests. */
    fun reset(userId: Long, serverId: Long) {
        hits.keys.removeIf { it.userId == userId && it.serverId == serverId }
    }

    /** Drops every window for [serverId], e.g. when the server is removed from Pano. */
    fun resetServer(serverId: Long) {
        hits.keys.removeIf { it.serverId == serverId }
    }

    // Entries whose window has fully elapsed are dead weight: without this sweep the map would
    // keep one deque per (action, user, server) triple that was ever used, for the life of the
    // process.
    private fun cleanUp(now: Long) {
        if (now - lastCleanupAt < CLEANUP_INTERVAL_MS) {
            return
        }

        lastCleanupAt = now

        hits.entries.removeIf { entry ->
            synchronized(entry.value) {
                entry.value.isEmpty() || entry.value.last() <= now - entry.key.action.windowMs
            }
        }
    }

    companion object {
        private const val CLEANUP_INTERVAL_MS = 60_000L

        /** The server id user-wide windows are kept under. Row ids start at 1. */
        private const val USER_WIDE_SERVER_ID = -1L
    }
}
