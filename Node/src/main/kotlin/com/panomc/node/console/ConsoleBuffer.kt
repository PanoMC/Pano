package com.panomc.node.console

/**
 * One managed server's console: the scrollback everyone gets and the budget nobody may exceed.
 *
 * The numbers are the console contract Pano and the Minecraft plugin already implement (AGENT.md
 * 2.4.1) -- 500 lines of local scrollback, batches of at most 100 flushed every 250 ms, a hard
 * ceiling of 500 lines per second, and a `dropped` counter for whatever the ceiling cost. They are
 * repeated here rather than negotiated because a server that decides to print a stack trace per
 * tick must not be able to take the node's socket down with it.
 *
 * Nothing in this class sends anything. It only decides what the next batch is, so the batching
 * rules can be tested without a socket, a process or a clock.
 */
class ConsoleBuffer(private val clock: () -> Long = System::currentTimeMillis) {
    /** Lines kept locally so a panel that opens later still sees how the server booted. */
    private val ring = ArrayDeque<ConsoleLine>()

    /** Lines waiting to go out. Sized to hold a full replay plus live headroom. */
    private val pending = ArrayDeque<ConsoleLine>()

    private val lock = Any()

    private var dropped = 0L
    private var windowStartedAt = 0L
    private var sentInWindow = 0
    private var streaming = false

    /** Whether Pano has asked for this server's console. */
    fun isStreaming(): Boolean = synchronized(lock) { streaming }

    /** Lines currently in the local scrollback, oldest first. */
    fun snapshot(): List<ConsoleLine> = synchronized(lock) { ring.toList() }

    /**
     * Records one line.
     *
     * Always lands in the scrollback, queues for sending only while streaming: buffering what
     * nobody asked for would mean a server that has been running for a week hands the first panel
     * to open a week of backlog.
     */
    fun add(line: ConsoleLine) {
        synchronized(lock) {
            ring.addLast(line)

            while (ring.size > RING_BUFFER_SIZE) {
                ring.removeFirst()
            }

            if (!streaming) {
                return
            }

            pending.addLast(line)

            while (pending.size > PENDING_LIMIT) {
                pending.removeFirst()

                dropped++
            }
        }
    }

    /**
     * Turns streaming on or off in response to `CONSOLE_STREAM`.
     *
     * Turning it on queues the whole scrollback first, oldest line first, so the panel opens on
     * history instead of on an empty screen.
     */
    fun setStreaming(enabled: Boolean) {
        synchronized(lock) {
            if (!enabled) {
                streaming = false

                pending.clear()

                dropped = 0

                return
            }

            if (streaming) {
                return
            }

            pending.clear()

            dropped = 0

            pending.addAll(ring)

            streaming = true
        }
    }

    /** Stops streaming but keeps the scrollback: the socket is gone, the server is not. */
    fun onDisconnect() = setStreaming(false)

    /** Whether there is anything worth a flush right now. */
    fun hasPending(): Boolean = synchronized(lock) { streaming && (pending.isNotEmpty() || dropped > 0L) }

    /** Whether the queue has grown past a full batch, which is what triggers an early flush. */
    fun isBatchReady(): Boolean = synchronized(lock) { streaming && pending.size >= BATCH_SIZE }

    /**
     * The next batch to send, or null when the per-second budget is spent or there is nothing
     * queued. The `dropped` count travels with the batch that follows the drops.
     */
    fun takeBatch(): Batch? = synchronized(lock) {
        if (!streaming) {
            return null
        }

        val budget = remainingBudgetLocked()

        if (budget <= 0) {
            return null
        }

        if (pending.isEmpty() && dropped == 0L) {
            return null
        }

        val limit = minOf(BATCH_SIZE, budget, pending.size)
        val taken = ArrayList<ConsoleLine>(limit)

        // A byte budget as well as a line count: a hundred lines of four kilobytes each would be
        // a single four-hundred-kilobyte frame, and both ends of this socket have a message-size
        // ceiling well below that. Stopping early only means one more batch on the next pass.
        var bytes = 0

        while (taken.size < limit) {
            val next = pending.first()

            if (taken.isNotEmpty() && bytes + next.m.length > MAX_BATCH_BYTES) {
                break
            }

            pending.removeFirst()

            taken.add(next)

            bytes += next.m.length
        }

        val take = taken.size

        val droppedInBatch = dropped

        dropped = 0
        sentInWindow += take

        Batch(taken, droppedInBatch)
    }

    /** How many more lines may be sent inside the current one-second window. Call under the lock. */
    private fun remainingBudgetLocked(): Int {
        val now = clock()

        if (now - windowStartedAt >= RATE_WINDOW_MILLIS) {
            windowStartedAt = now
            sentInWindow = 0
        }

        return MAX_LINES_PER_SECOND - sentInWindow
    }

    /** One flush worth of lines plus how many were thrown away to make room for them. */
    data class Batch(val lines: List<ConsoleLine>, val dropped: Long)

    companion object {
        const val RING_BUFFER_SIZE = 500
        const val PENDING_LIMIT = 2_000
        const val BATCH_SIZE = 100
        const val FLUSH_INTERVAL_MILLIS = 250L
        const val MAX_LINES_PER_SECOND = 500

        /** Rough ceiling on the text in one batch, so no frame gets near either side's limit. */
        const val MAX_BATCH_BYTES = 48 * 1024

        private const val RATE_WINDOW_MILLIS = 1_000L
    }
}
