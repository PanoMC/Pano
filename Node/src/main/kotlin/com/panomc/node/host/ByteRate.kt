package com.panomc.node.host

/**
 * Bytes per second out of a cumulative byte counter read once per metrics tick (§2.4.22 A).
 *
 * Every counter this node reads — the kernel's per interface, Docker's per container — only ever
 * counts up, so a rate is the difference between two readings over the time between them. That
 * makes the first reading worthless on its own: it reports null, and the panel shows a dash for one
 * tick instead of a number it made up.
 *
 * A counter that went backwards was reset — a container restarted, an interface came back, a 32-bit
 * counter wrapped — and the difference across a reset means nothing, so that tick is null too and
 * the new value becomes the baseline. Not thread-safe; one meter belongs to one tick loop.
 */
class ByteRate {
    private var previous: Reading? = null

    private data class Reading(val bytes: Long, val at: Long)

    /** The rate since the previous reading, or null when there is no usable previous one. */
    fun rate(total: Long?, at: Long): Long? {
        if (total == null || total < 0) {
            reset()

            return null
        }

        val last = previous

        previous = Reading(total, at)

        if (last == null) {
            return null
        }

        val elapsed = at - last.at
        val delta = total - last.bytes

        if (elapsed <= 0 || delta < 0) {
            return null
        }

        return delta * 1000L / elapsed
    }

    /** Forgets the baseline, so the next reading starts over instead of spanning a gap. */
    fun reset() {
        previous = null
    }
}
