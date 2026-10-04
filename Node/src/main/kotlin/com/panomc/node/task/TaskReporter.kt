package com.panomc.node.task

import com.panomc.node.net.NodeProtocol
import com.panomc.node.net.PlatformConnection
import io.vertx.core.json.JsonObject
import java.util.concurrent.ConcurrentHashMap

/**
 * The three things a long job says about itself, as an interface so a job can be exercised in a
 * test without a socket behind it.
 */
interface TaskSink {
    fun running(taskId: String, serverUuid: String?, kind: String, percent: Int, message: String?)

    /**
     * A RUNNING frame of a download, with its bytes, size and rate for the panel's progress line.
     * A sink that has nowhere to put them reports a plain [running].
     */
    fun transfer(
        taskId: String,
        serverUuid: String?,
        kind: String,
        percent: Int,
        message: String?,
        progress: com.panomc.node.util.Downloader.Progress
    ) = running(taskId, serverUuid, kind, percent, message)

    /**
     * A line of a tool's own output -- BuildTools' Maven log -- rather than a step of the task:
     * the panel lists the steps and keeps these in the task's output log. A sink that does not
     * tell the two apart reports a plain [running].
     */
    fun output(taskId: String, serverUuid: String?, kind: String, percent: Int, line: String?) =
        running(taskId, serverUuid, kind, percent, line)

    /**
     * Runs [block] -- one task's work -- with every download it makes on this thread reported as
     * [transfer] frames at the step it is on. A sink that has nowhere to put bytes just runs it.
     */
    fun <T> watchingDownloads(taskId: String, serverUuid: String?, kind: String, block: () -> T): T = block()

    fun done(taskId: String, serverUuid: String?, kind: String, message: String?, extra: JsonObject? = null)

    fun failed(taskId: String, serverUuid: String?, kind: String, error: String, extra: JsonObject? = null)
}

/**
 * Reports how a long job is going, makes sure it is only ever finished once, and keeps the
 * reporting down to what a panel can actually show.
 *
 * Pano treats DONE and FAILED as terminal: an install that reports DONE turns an INSTALLING row
 * into a startable server, and a delete that reports DONE is what finally removes the row. A
 * second terminal frame for the same task would therefore either resurrect work that has already
 * had its consequences or, worse, apply them twice. The guard lives here rather than in every
 * caller so no future task kind can forget it.
 *
 * RUNNING frames go through a [TaskProgressThrottle] for the opposite reason: callers report
 * progress as often as they learn about it (a download reports every percent), and Pano handles
 * every frame it receives separately. The throttle is what turns eighty frames in two seconds into
 * a handful, and the last held frame is flushed just before the terminal one so the panel's final
 * RUNNING is the real last step rather than whichever one happened to land on the interval.
 */
class TaskReporter(
    private val connection: PlatformConnection,
    private val throttle: TaskProgressThrottle = TaskProgressThrottle(),
    /**
     * Told which server a finished task was about, for whatever caches its files invalidate
     * (§2.4.18 A: the directory a restore, import, reinstall or delete just rewrote is no longer
     * the size it was measured at).
     *
     * Here for the same reason the finished-once guard below is: every task kind already reports
     * its end through this one method, so a hook here is one no future task can forget.
     */
    private val onServerTaskFinished: (String) -> Unit = {}
) : TaskSink {
    private val finished = ConcurrentHashMap.newKeySet<String>()

    /** The step each running task last reported, for the downloads [watchingDownloads] sees. */
    private val steps = ConcurrentHashMap<String, Pair<Int, String?>>()

    override fun running(taskId: String, serverUuid: String?, kind: String, percent: Int, message: String?) {
        if (taskId in finished) {
            return
        }

        steps[taskId] = percent to message

        val frame = throttle.offer(taskId, percent.coerceIn(0, 99), message) ?: return

        send(taskId, serverUuid, kind, "RUNNING", frame.percent, frame.message, null, extraOf(frame))
    }

    override fun output(taskId: String, serverUuid: String?, kind: String, percent: Int, line: String?) {
        if (taskId in finished) {
            return
        }

        // The percentage moves on, the step a download would be reported at stays the step.
        steps[taskId] = percent to steps[taskId]?.second

        val frame = throttle.offer(taskId, percent.coerceIn(0, 99), line, output = true) ?: return

        send(taskId, serverUuid, kind, "RUNNING", frame.percent, frame.message, null, extraOf(frame))
    }

    override fun transfer(
        taskId: String,
        serverUuid: String?,
        kind: String,
        percent: Int,
        message: String?,
        progress: com.panomc.node.util.Downloader.Progress
    ) {
        if (taskId in finished) {
            return
        }

        val frame = throttle.offer(taskId, percent.coerceIn(0, 99), message, progress) ?: return

        send(taskId, serverUuid, kind, "RUNNING", frame.percent, frame.message, null, extraOf(frame))
    }

    override fun <T> watchingDownloads(taskId: String, serverUuid: String?, kind: String, block: () -> T): T =
        com.panomc.node.util.Downloader.observing({ progress ->
            val (percent, message) = steps[taskId] ?: (0 to null)

            transfer(taskId, serverUuid, kind, percent, message, progress)
        }, block)

    /**
     * What a frame carries beyond the usual fields, flat on it: a download's bytes, and `output`
     * on a tool's output line. Nothing for every other frame.
     */
    private fun extraOf(frame: TaskProgressThrottle.Frame): JsonObject? {
        val progress = frame.transfer

        if (progress == null && !frame.output) {
            return null
        }

        val extra = JsonObject()

        if (progress != null) {
            extra
                .put("bytesDone", progress.done)
                .put("bytesTotal", progress.total.takeIf { it > 0 })
                .put("bytesPerSecond", progress.bytesPerSecond)
        }

        if (frame.output) {
            extra.put("output", true)
        }

        return extra
    }

    /**
     * Reports a task as finished, optionally with [extra] facts about how it finished.
     *
     * [extra] is merged into the frame rather than squeezed into [message]: an install that
     * succeeded but could not link the server is still a DONE, and the panel needs to be able to
     * act on that (`pluginInstalled: false`) rather than parse an English sentence.
     */
    override fun done(
        taskId: String,
        serverUuid: String?,
        kind: String,
        message: String?,
        extra: JsonObject?
    ) {
        if (!finished.add(taskId)) {
            return
        }

        flushRunning(taskId, serverUuid, kind)

        steps.remove(taskId)

        send(taskId, serverUuid, kind, "DONE", 100, message, null, extra)

        serverUuid?.let(onServerTaskFinished)
    }

    /**
     * Reports a task as failed. [extra] carries what a panel can act on beside the error code, such
     * as the servers a `JAVA_REMOVE` refused over (`serverUuids`).
     */
    override fun failed(taskId: String, serverUuid: String?, kind: String, error: String, extra: JsonObject?) {
        if (!finished.add(taskId)) {
            return
        }

        flushRunning(taskId, serverUuid, kind)

        steps.remove(taskId)

        send(taskId, serverUuid, kind, "FAILED", 100, null, error, extra)

        // A failure gets the hook too: a restore that died halfway still left different files
        // behind than the ones that were measured.
        serverUuid?.let(onServerTaskFinished)
    }

    /**
     * Sends whatever progress the throttle was still holding, so the step a task ended on is not
     * the one the panel is left looking at while a DONE arrives on top of it.
     */
    private fun flushRunning(taskId: String, serverUuid: String?, kind: String) {
        val held = throttle.flush(taskId)

        throttle.forget(taskId)

        if (held != null) {
            send(taskId, serverUuid, kind, "RUNNING", held.percent, held.message, null, extraOf(held))
        }
    }

    private fun send(
        taskId: String,
        serverUuid: String?,
        kind: String,
        status: String,
        percent: Int,
        message: String?,
        error: String?,
        extra: JsonObject? = null
    ) {
        val payload = JsonObject()
            .put("taskId", taskId)
            .put("serverUuid", serverUuid)
            .put("kind", kind)
            .put("status", status)
            .put("percent", percent)
            .put("message", message)
            .put("error", error)

        extra?.forEach { entry -> payload.put(entry.key, entry.value) }

        connection.send(NodeProtocol.Outbound.TASK_PROGRESS, payload)
    }
}
