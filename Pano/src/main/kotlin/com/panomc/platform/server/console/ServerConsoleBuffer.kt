package com.panomc.platform.server.console

import com.panomc.platform.server.dto.ConsoleLineData

/**
 * Fixed-size in-memory history of one server's console output.
 *
 * A panel that opens the console page wants the recent past, not just what happens from now on, so
 * every batch a server pushes lands here first and is served back by
 * `GET /api/panel/servers/:id/console`. The buffer is a ring: once [capacity] lines are stored the
 * oldest line is evicted for every new one, which bounds the memory a chatty server can take
 * regardless of how long it stays connected.
 *
 * Buffers deliberately survive a reconnect. A server that restarts keeps the lines that led up to
 * the restart, which is exactly the window an admin wants to look at, and the ring cap means
 * keeping them costs nothing extra. They are dropped only when the server itself is removed from
 * Pano (see `ServerManager.onServerDeleted`).
 *
 * Every method is synchronized: lines arrive on the event-loop thread that owns the plugin socket
 * while the REST endpoint reads the history from whichever thread served that request.
 */
class ServerConsoleBuffer(private val capacity: Int = DEFAULT_CAPACITY) {
    private val lines = ArrayDeque<ConsoleLineData>()

    private var dropped = 0L

    init {
        require(capacity > 0) { "capacity must be greater than 0" }
    }

    /**
     * Appends [batch] and records [reportedDropped] lines the sender had to throw away before they
     * ever reached Pano. Lines evicted here to stay within [capacity] are not counted as dropped:
     * nothing was lost from the live stream, only from the history.
     */
    @Synchronized
    fun add(batch: List<ConsoleLineData>, reportedDropped: Long = 0) {
        if (reportedDropped > 0) {
            dropped += reportedDropped
        }

        batch.forEach { line ->
            lines.addLast(line)

            if (lines.size > capacity) {
                lines.removeFirst()
            }
        }
    }

    /** The newest [limit] lines, oldest first. A [limit] of 0 or less returns nothing. */
    @Synchronized
    fun snapshot(limit: Int = capacity): List<ConsoleLineData> {
        if (limit <= 0) {
            return emptyList()
        }

        if (limit >= lines.size) {
            return lines.toList()
        }

        return lines.toList().subList(lines.size - limit, lines.size)
    }

    /** Total number of lines the sender reported as dropped since this buffer was created. */
    @Synchronized
    fun getDroppedCount() = dropped

    @Synchronized
    fun size() = lines.size

    @Synchronized
    fun clear() {
        lines.clear()
        dropped = 0
    }

    companion object {
        /** Lines kept per server, matching the console contract in AGENT.md. */
        const val DEFAULT_CAPACITY = 2000
    }
}
