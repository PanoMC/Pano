package com.panomc.node.task

import java.nio.channels.ClosedByInterruptException
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap

/**
 * The tasks this daemon has told Pano it is working on and not yet finished.
 *
 * A task enters with its first RUNNING frame and leaves with its DONE or FAILED one, which is
 * exactly the span during which Pano's row says RUNNING. Three things need that set:
 *
 * - the way out ([TaskReporter.failInFlight]): a daemon that stops or restarts while a plugin is
 *   downloading must say the task failed before the socket closes. Otherwise the row stays RUNNING
 *   until Pano's ten-minute sweep, and the panel shows a spinner for work nothing is doing;
 * - the self update ([SelfUpdateService]): the restart that applies an update waits for these to
 *   end, so an update never takes a running install down with it;
 * - the hello (`runningTasks`): a daemon that just started lists none, and Pano fails whatever it
 *   still believed was running there.
 */
class InFlightTasks {
    data class Entry(val taskId: String, val serverUuid: String?, val kind: String)

    private val entries = ConcurrentHashMap<String, Entry>()

    fun started(taskId: String, serverUuid: String?, kind: String) {
        entries.putIfAbsent(taskId, Entry(taskId, serverUuid, kind))
    }

    fun ended(taskId: String) {
        entries.remove(taskId)
    }

    fun all(): List<Entry> = entries.values.toList()

    /** How many tasks are in flight, leaving out those of [excludingKind]. */
    fun count(excludingKind: String? = null): Int = entries.values.count { it.kind != excludingKind }

    companion object {
        /** What a task that the daemon's stop or restart cut short is failed with. */
        const val INTERRUPTED_ERROR = "Interrupted: the node stopped or restarted before this task finished."

        /**
         * The error a task that died of [throwable] is reported with.
         *
         * An interrupt carries no message, so `exception.message` alone made the panel (and the
         * log) say `null`: an interrupt is the daemon going down around the task, and says so.
         */
        fun errorOf(throwable: Throwable): String =
            if (isInterruption(throwable)) INTERRUPTED_ERROR else throwable.message ?: throwable.javaClass.simpleName

        fun isInterruption(throwable: Throwable): Boolean {
            var current: Throwable? = throwable
            var depth = 0

            while (current != null && depth < 8) {
                if (current is InterruptedException ||
                    current is CancellationException ||
                    current is ClosedByInterruptException
                ) {
                    return true
                }

                current = current.cause
                depth++
            }

            return false
        }
    }
}
