package com.panomc.node.task

import java.util.concurrent.ConcurrentHashMap

/**
 * Decides which of a task's RUNNING frames are worth putting on the wire.
 *
 * A download reports its fraction continuously, so an install used to emit about eighty
 * `TASK_PROGRESS` frames in the two seconds it takes to pull a jar over a fast line. Pano handles
 * each frame in its own coroutine, so a burst like that is how a progress bar ends up showing 60,
 * 61, 54, 55 -- and there is nothing in eighty frames a person can read anyway.
 *
 * A frame is sent when there is something new to say:
 * - the first frame of a task, because the panel has nothing yet;
 * - the message changed, which is the step name people actually read ("Downloading" to
 *   "Configuring") and must never be swallowed;
 * - the percentage advanced by at least [MIN_PERCENT_STEP] points;
 * - or [MIN_INTERVAL_MS] have passed since the last one, so a slow download still moves.
 *
 * Everything else is held rather than dropped: [flush] hands the last swallowed frame back so a
 * task can send it just before its DONE or FAILED, and the panel's last RUNNING is the real last
 * one instead of whichever happened to fall on the interval.
 *
 * [clock] is injected only so the rules can be tested without sleeping.
 */
class TaskProgressThrottle(private val clock: () -> Long = System::currentTimeMillis) {
    /** A RUNNING frame's payload, as far as the throttle cares about it; [transfer] rides along. */
    data class Frame(
        val percent: Int,
        val message: String?,
        val transfer: com.panomc.node.util.Downloader.Progress? = null,
        /** A tool's own output line rather than a step of the task ([TaskSink.output]). */
        val output: Boolean = false
    )

    private data class State(
        val sentAt: Long,
        val sentPercent: Int,
        val sentMessage: String?,
        val held: Frame?,
        /** Whether the last frame sent carried a download's bytes. */
        val sentTransfer: Boolean = false
    )

    private val states = ConcurrentHashMap<String, State>()

    /** The frame to send for [taskId], or `null` when this one is held back. */
    fun offer(
        taskId: String,
        percent: Int,
        message: String?,
        transfer: com.panomc.node.util.Downloader.Progress? = null,
        output: Boolean = false
    ): Frame? {
        val now = clock()
        val frame = Frame(percent, message, transfer, output)

        var send: Frame? = null

        states.compute(taskId) { _, state ->
            if (state == null || shouldSend(state, frame, now)) {
                send = frame

                State(
                    sentAt = now,
                    sentPercent = frame.percent,
                    sentMessage = frame.message,
                    held = null,
                    sentTransfer = frame.transfer != null
                )
            } else {
                state.copy(held = frame)
            }
        }

        return send
    }

    /**
     * The last frame [offer] held back for [taskId], or `null` when nothing is waiting.
     *
     * Returns it once: a flushed frame is a sent frame.
     */
    fun flush(taskId: String): Frame? {
        var held: Frame? = null

        states.computeIfPresent(taskId) { _, state ->
            held = state.held

            state.copy(held = null)
        }

        return held
    }

    /** Drops everything remembered about [taskId]; a task only ends once. */
    fun forget(taskId: String) {
        states.remove(taskId)
    }

    // The first bytes of a download go out at once: a small file (BuildTools is 3.6 MB) is done in
    // under the interval, and holding its frames until the next step replaced them is how
    // "Downloading BuildTools" never said how much or how fast.
    private fun shouldSend(state: State, frame: Frame, now: Long) =
        frame.message != state.sentMessage ||
                (frame.transfer != null && !state.sentTransfer) ||
                frame.percent >= state.sentPercent + MIN_PERCENT_STEP ||
                now - state.sentAt >= MIN_INTERVAL_MS

    companion object {
        /** Two frames a second is as fast as a progress bar is worth redrawing. */
        const val MIN_INTERVAL_MS = 500L

        /** A jump this big is worth a frame of its own, however recent the last one was. */
        const val MIN_PERCENT_STEP = 5
    }
}
