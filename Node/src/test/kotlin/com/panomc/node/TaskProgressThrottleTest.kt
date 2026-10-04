package com.panomc.node

import com.panomc.node.task.TaskProgressThrottle
import com.panomc.node.task.TaskProgressThrottle.Frame
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TaskProgressThrottleTest {
    private var now = 1_000L

    private val throttle = TaskProgressThrottle { now }

    @Test
    fun `always sends the first frame of a task`() {
        assertEquals(Frame(1, "Preparing"), throttle.offer(TASK, 1, "Preparing"))
    }

    @Test
    fun `holds a frame that is neither newer nor bigger`() {
        throttle.offer(TASK, 10, "Downloading")

        now += 100

        assertNull(throttle.offer(TASK, 11, "Downloading"))
        assertNull(throttle.offer(TASK, 12, "Downloading"))
    }

    @Test
    fun `sends when the percentage advances by five points`() {
        throttle.offer(TASK, 10, "Downloading")

        now += 10

        assertNull(throttle.offer(TASK, 14, "Downloading"))
        assertEquals(Frame(15, "Downloading"), throttle.offer(TASK, 15, "Downloading"))
    }

    @Test
    fun `sends a changed message however recent the last frame was`() {
        throttle.offer(TASK, 78, "Downloading")

        now += 1

        assertEquals(Frame(78, "Configuring"), throttle.offer(TASK, 78, "Configuring"))
    }

    @Test
    fun `sends again once the interval has passed`() {
        throttle.offer(TASK, 10, "Downloading")

        now += TaskProgressThrottle.MIN_INTERVAL_MS - 1

        assertNull(throttle.offer(TASK, 11, "Downloading"))

        now += 1

        assertEquals(Frame(12, "Downloading"), throttle.offer(TASK, 12, "Downloading"))
    }

    @Test
    fun `hands the last held frame back exactly once`() {
        throttle.offer(TASK, 10, "Downloading")

        now += 10

        throttle.offer(TASK, 11, "Downloading")
        throttle.offer(TASK, 12, "Downloading")

        assertEquals(Frame(12, "Downloading"), throttle.flush(TASK))
        assertNull(throttle.flush(TASK))
    }

    @Test
    fun `has nothing to flush when the last frame went out`() {
        throttle.offer(TASK, 10, "Downloading")

        assertNull(throttle.flush(TASK))
    }

    @Test
    fun `forgets a task so a later one under the same id starts fresh`() {
        throttle.offer(TASK, 10, "Downloading")
        throttle.forget(TASK)

        assertEquals(Frame(11, "Downloading"), throttle.offer(TASK, 11, "Downloading"))
    }

    @Test
    fun `throttles each task on its own`() {
        throttle.offer(TASK, 10, "Downloading")

        now += 10

        assertNotNull(throttle.offer(OTHER_TASK, 11, "Downloading"))
        assertNull(throttle.offer(TASK, 11, "Downloading"))
    }

    @Test
    fun `cuts a one percent at a time download down to a handful of frames`() {
        // What an install actually does: eighty frames in about two seconds, one per percent.
        val sent = mutableListOf<Frame>()

        for (percent in 1..80) {
            throttle.offer(TASK, percent, "Downloading")?.let { sent.add(it) }

            now += 25
        }

        val last = throttle.flush(TASK)

        assertTrue(sent.size <= 20, "sent ${sent.size} frames, expected the burst to be cut down")
        assertTrue(sent.size >= 2, "sent ${sent.size} frames, expected progress to still move")

        // Nothing walks backwards, the step name survived, and the end of the download is not lost
        // just because it fell between two frames.
        assertEquals(sent.map { it.percent }.sorted(), sent.map { it.percent })
        assertTrue(sent.all { it.message == "Downloading" })
        assertEquals(80, last?.percent ?: sent.last().percent)
    }

    companion object {
        private const val TASK = "task-1"
        private const val OTHER_TASK = "task-2"
    }
}
