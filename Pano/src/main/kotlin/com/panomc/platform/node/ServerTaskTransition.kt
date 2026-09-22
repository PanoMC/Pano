package com.panomc.platform.node

/**
 * The rules a task's progress has to obey before it is written to the database.
 *
 * Progress arrives over a socket that can reorder nothing but can certainly repeat and can arrive
 * after Pano already gave up on a task, so this exists to make sure a finished task never comes
 * back to life and a percentage never walks backwards. A rejected update is dropped, not an error:
 * the node is not misbehaving, it is just late.
 */
object ServerTaskTransition {
    /** The stored shape of a task's progress. */
    data class Progress(val status: ServerTaskStatus, val percent: Int)

    /**
     * Applies a node-reported update to [current], or returns `null` when the update must be
     * ignored.
     *
     * [reportedPercent] is clamped to 0..100, never allowed to decrease while the task is still
     * running, and forced to 100 once the task is DONE so the panel's progress bar cannot finish
     * short of the end.
     */
    fun apply(current: Progress, reportedStatus: ServerTaskStatus?, reportedPercent: Int?): Progress? {
        if (reportedStatus == null) {
            return null
        }

        // A task that already ended is final. Anything after that is a duplicate or a message from
        // before Pano recorded the end, and re-opening it would resurrect a row the panel and the
        // server row have already moved on from.
        if (current.status.isTerminal) {
            return null
        }

        // PENDING is Pano's own bookkeeping state and is never reported back by a node.
        if (reportedStatus == ServerTaskStatus.PENDING) {
            return null
        }

        val clamped = (reportedPercent ?: current.percent).coerceIn(0, 100)

        val percent = when (reportedStatus) {
            ServerTaskStatus.DONE -> 100
            ServerTaskStatus.FAILED -> current.percent
            else -> maxOf(clamped, current.percent)
        }

        if (reportedStatus == current.status && percent == current.percent) {
            return null
        }

        return Progress(reportedStatus, percent)
    }
}
