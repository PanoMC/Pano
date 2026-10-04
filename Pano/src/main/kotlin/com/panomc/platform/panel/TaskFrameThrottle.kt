package com.panomc.platform.panel

import java.util.concurrent.ConcurrentHashMap

/**
 * Caps the `taskProgress` frames one task sends to the server-list audiences (SM-68, §2.4.33).
 *
 * The task's creator and the nodes page get every frame, as before. The Servers modal and a server
 * page that merely happen to show the server get at most one per [intervalMs] per task: a node
 * reporting download bytes can send dozens a second, and every open panel re-rendering a card for
 * each of them buys nothing. What is held back is not dropped — the newest held frame goes out
 * when the interval is up ([flush]) — and an end state always goes out at once, so a card never
 * keeps a bar for a task that is finished.
 *
 * Pure bookkeeping with the clock passed in; the hub owns the timer.
 */
class TaskFrameThrottle(private val intervalMs: Long = DEFAULT_INTERVAL_MS) {
    sealed interface Offer {
        /** Write the frame now. */
        data object Send : Offer

        /** Held; when [scheduleInMs] is set, arm a timer for that long and then call [flush]. */
        data class Hold(val scheduleInMs: Long?) : Offer
    }

    private val lastSent = ConcurrentHashMap<String, Long>()

    /** The newest frame held back per task, sent by [flush]. */
    private val held = ConcurrentHashMap<String, String>()

    fun offer(taskUuid: String, frame: String, terminal: Boolean, now: Long): Offer {
        if (terminal) {
            // Whatever was held is older than this; the end is the only thing worth saying.
            held.remove(taskUuid)
            lastSent.remove(taskUuid)

            return Offer.Send
        }

        val last = lastSent[taskUuid]

        if (last == null || now - last >= intervalMs) {
            held.remove(taskUuid)
            lastSent[taskUuid] = now

            return Offer.Send
        }

        val alreadyArmed = held.put(taskUuid, frame) != null

        return Offer.Hold(if (alreadyArmed) null else intervalMs - (now - last))
    }

    /** The frame held for [taskUuid], now due, or null when nothing is (an end state overtook it). */
    fun flush(taskUuid: String, now: Long): String? {
        val frame = held.remove(taskUuid) ?: return null

        lastSent[taskUuid] = now

        return frame
    }

    companion object {
        /** Two frames a second. */
        const val DEFAULT_INTERVAL_MS = 500L
    }
}
