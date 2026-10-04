package com.panomc.platform.node

import com.panomc.platform.node.ServerTaskTransition.Progress
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ServerTaskTransitionTest {
    @Test
    fun `starts a pending task running`() {
        val result = ServerTaskTransition.apply(
            Progress(ServerTaskStatus.PENDING, 0),
            ServerTaskStatus.RUNNING,
            10
        )

        assertEquals(Progress(ServerTaskStatus.RUNNING, 10), result)
    }

    @Test
    fun `advances the percentage while running`() {
        val result = ServerTaskTransition.apply(
            Progress(ServerTaskStatus.RUNNING, 10),
            ServerTaskStatus.RUNNING,
            55
        )

        assertEquals(Progress(ServerTaskStatus.RUNNING, 55), result)
    }

    @Test
    fun `never lets the percentage walk backwards`() {
        assertNull(
            ServerTaskTransition.apply(
                Progress(ServerTaskStatus.RUNNING, 55),
                ServerTaskStatus.RUNNING,
                20
            )
        )
    }

    @Test
    fun `keeps a frame at the same percentage, which carries the step's newer line`() {
        // A BuildTools output line, a heartbeat or a download's bytes: the percentage does not
        // move, the frame is still news.
        assertEquals(
            Progress(ServerTaskStatus.RUNNING, 55),
            ServerTaskTransition.apply(
                Progress(ServerTaskStatus.RUNNING, 55),
                ServerTaskStatus.RUNNING,
                55
            )
        )
        assertEquals(
            Progress(ServerTaskStatus.RUNNING, 55),
            ServerTaskTransition.apply(Progress(ServerTaskStatus.RUNNING, 55), ServerTaskStatus.RUNNING, null)
        )
    }

    @Test
    fun `forces a finished task to a hundred percent`() {
        val result = ServerTaskTransition.apply(
            Progress(ServerTaskStatus.RUNNING, 40),
            ServerTaskStatus.DONE,
            40
        )

        assertEquals(Progress(ServerTaskStatus.DONE, 100), result)
    }

    @Test
    fun `keeps the percentage a failure stopped at`() {
        val result = ServerTaskTransition.apply(
            Progress(ServerTaskStatus.RUNNING, 40),
            ServerTaskStatus.FAILED,
            90
        )

        assertEquals(Progress(ServerTaskStatus.FAILED, 40), result)
    }

    @Test
    fun `refuses to reopen a finished task`() {
        assertNull(
            ServerTaskTransition.apply(Progress(ServerTaskStatus.DONE, 100), ServerTaskStatus.RUNNING, 50)
        )
        assertNull(
            ServerTaskTransition.apply(Progress(ServerTaskStatus.FAILED, 30), ServerTaskStatus.RUNNING, 50)
        )
        assertNull(
            ServerTaskTransition.apply(Progress(ServerTaskStatus.DONE, 100), ServerTaskStatus.FAILED, 100)
        )
    }

    @Test
    fun `refuses a status it cannot read`() {
        assertNull(ServerTaskTransition.apply(Progress(ServerTaskStatus.RUNNING, 10), null, 50))
    }

    @Test
    fun `refuses a node claiming a task is pending`() {
        assertNull(
            ServerTaskTransition.apply(Progress(ServerTaskStatus.RUNNING, 10), ServerTaskStatus.PENDING, 50)
        )
    }

    @Test
    fun `clamps an out of range percentage`() {
        assertEquals(
            Progress(ServerTaskStatus.RUNNING, 100),
            ServerTaskTransition.apply(Progress(ServerTaskStatus.RUNNING, 10), ServerTaskStatus.RUNNING, 4000)
        )
        assertEquals(
            Progress(ServerTaskStatus.RUNNING, 10),
            ServerTaskTransition.apply(Progress(ServerTaskStatus.PENDING, 10), ServerTaskStatus.RUNNING, -50)
        )
    }

    @Test
    fun `applies an out of order burst as a forwards only sequence`() {
        // What a download's frames look like once concurrent handlers have reordered them: the
        // percentages arrive interleaved and a late RUNNING trails the DONE.
        val frames = listOf(
            ServerTaskStatus.RUNNING to 60,
            ServerTaskStatus.RUNNING to 61,
            ServerTaskStatus.RUNNING to 54,
            ServerTaskStatus.RUNNING to 55,
            ServerTaskStatus.RUNNING to 78,
            ServerTaskStatus.RUNNING to 62,
            ServerTaskStatus.DONE to 80,
            ServerTaskStatus.RUNNING to 79
        )

        var stored = Progress(ServerTaskStatus.RUNNING, 50)
        val applied = mutableListOf<Progress>()

        frames.forEach { (status, percent) ->
            val next = ServerTaskTransition.apply(stored, status, percent) ?: return@forEach

            stored = next
            applied.add(next)
        }

        assertEquals(
            listOf(
                Progress(ServerTaskStatus.RUNNING, 60),
                Progress(ServerTaskStatus.RUNNING, 61),
                Progress(ServerTaskStatus.RUNNING, 78),
                Progress(ServerTaskStatus.DONE, 100)
            ),
            applied
        )
        assertEquals(applied.map { it.percent }.sorted(), applied.map { it.percent })
        assertEquals(Progress(ServerTaskStatus.DONE, 100), stored)
    }

    @Test
    fun `keeps the stored percentage when none is reported`() {
        assertEquals(
            Progress(ServerTaskStatus.RUNNING, 30),
            ServerTaskTransition.apply(Progress(ServerTaskStatus.PENDING, 30), ServerTaskStatus.RUNNING, null)
        )
    }
}
