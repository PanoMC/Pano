package com.panomc.platform.server.console

import com.panomc.platform.server.dto.ConsoleLineData
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The case these exist for: a managed server crashed with nobody watching its console, so every
 * line explaining it was in the node's ring buffer and none of it in Pano's.
 */
class NodeConsoleHistoryTest {
    private fun local(t: Long, m: String, level: String = "INFO") =
        ConsoleLineData(t, level, m, ConsoleLineData.SRC_PLUGIN)

    private fun node(t: Long, m: String, level: String = "INFO") =
        ConsoleLineData(t, level, m, ConsoleLineData.SRC_NODE)

    @Test
    fun `puts the history the source read in front of the lines Pano holds`() {
        val merged = NodeConsoleHistory.merge(
            listOf(local(400, "player joined"), local(500, "crash")),
            listOf(node(100, "Starting server"), node(200, "Done (5s)"), node(400, "player joined")),
            limit = 10
        )

        assertEquals(listOf("Starting server", "Done (5s)", "player joined", "crash"), merged.map { it.m })
    }

    @Test
    fun `marks what came from the source and keeps what did not`() {
        val merged = NodeConsoleHistory.merge(listOf(local(200, "b")), listOf(node(100, "a")), limit = 10)

        assertEquals(ConsoleLineData.SRC_NODE, merged.first().src)
        assertEquals(ConsoleLineData.SRC_PLUGIN, merged.last().src)
    }

    @Test
    fun `drops the source's copy of the lines Pano already has`() {
        // The windows meet: the source kept the same lines it streamed while somebody watched.
        val merged = NodeConsoleHistory.merge(
            listOf(local(100, "shared"), local(200, "also shared")),
            listOf(node(50, "only the source saw this"), node(100, "shared"), node(200, "also shared")),
            limit = 10
        )

        assertEquals(listOf("only the source saw this", "shared", "also shared"), merged.map { it.m })

        // Pano's copies are the ones kept, so its labels survive the join.
        assertEquals(listOf(ConsoleLineData.SRC_PLUGIN, ConsoleLineData.SRC_PLUGIN), merged.drop(1).map { it.src })
    }

    @Test
    fun `finds the overlap although the source replayed it off disk with a coarser clock`() {
        // What a source does after losing its ring buffer: the same lines, read back out of a log
        // file whose timestamps stop at the second. Matching (t, m) finds nothing in common here
        // and shows the operator every line twice.
        val merged = NodeConsoleHistory.merge(
            listOf(local(1_758_000_001_234, "Done (5s)"), local(1_758_000_002_987, "player joined")),
            listOf(
                node(1_758_000_000_000, "Starting server"),
                node(1_758_000_001_000, "Done (5s)"),
                node(1_758_000_002_000, "player joined")
            ),
            limit = 10
        )

        assertEquals(listOf("Starting server", "Done (5s)", "player joined"), merged.map { it.m })
        assertEquals(1_758_000_001_234, merged[1].t)
    }

    @Test
    fun `prefers the longest overlap when a line repeats`() {
        val merged = NodeConsoleHistory.merge(
            listOf("same", "same", "same", "b").mapIndexed { index, m -> local(index.toLong(), m) },
            listOf("a", "same", "same", "same").mapIndexed { index, m -> node(index.toLong(), m) },
            limit = 10
        )

        assertEquals(listOf("a", "same", "same", "same", "b"), merged.map { it.m })
    }

    @Test
    fun `keeps both halves whole when nothing overlaps`() {
        val merged = NodeConsoleHistory.merge(
            listOf(local(30, "new one"), local(40, "new two")),
            listOf(node(10, "old one"), node(20, "old two")),
            limit = 10
        )

        assertEquals(listOf("old one", "old two", "new one", "new two"), merged.map { it.m })
    }

    @Test
    fun `keeps the newest lines when the merge exceeds the limit`() {
        val merged = NodeConsoleHistory.merge(
            listOf(local(30, "three"), local(40, "four")),
            listOf(node(10, "one"), node(20, "two")),
            limit = 3
        )

        assertEquals(listOf("two", "three", "four"), merged.map { it.m })
        assertTrue(NodeConsoleHistory.merge(listOf(local(10, "one")), emptyList(), limit = 0).isEmpty())
        assertEquals(listOf("two"), NodeConsoleHistory.merge(emptyList(), listOf(node(10, "one"), node(20, "two")), 1).map { it.m })
    }

    @Test
    fun `reads a plugin's page and labels every line of it as the plugin's`() {
        val parsed = NodeConsoleHistory.parsePlugin(
            listOf(
                ConsoleLineData(1_758_000_000_000L, "error", "boom", ConsoleLineData.SRC_NODE),
                ConsoleLineData(0L, "SHOUT", "no level, no clock", ConsoleLineData.SRC_NODE)
            ),
            now = 1_758_000_009_999L
        )

        assertEquals(2, parsed.size)
        assertEquals("ERROR", parsed.first().l)
        assertEquals("INFO", parsed.last().l)
        assertEquals(1_758_000_009_999L, parsed.last().t)

        // The label is Pano's statement about which stream a line arrived on, so what the payload
        // claimed about itself is thrown away.
        assertTrue(parsed.all { it.src == ConsoleLineData.SRC_PLUGIN })
        assertTrue(NodeConsoleHistory.parsePlugin(null).isEmpty())
        assertTrue(NodeConsoleHistory.parsePlugin(emptyList()).isEmpty())
    }

    @Test
    fun `serves Pano's own history untouched when the source has nothing`() {
        val local = listOf(local(10, "one"), local(20, "two"))

        assertEquals(local, NodeConsoleHistory.merge(local, emptyList(), limit = 10))
    }

    @Test
    fun `reads a reply and normalises every line of it`() {
        val payload = JsonObject()
            .put("ok", true)
            .put(
                "lines",
                JsonArray()
                    .add(JsonObject().put("t", 1758000000000L).put("l", "error").put("m", "boom"))
                    .add(JsonObject().put("t", 0).put("l", "SHOUT").put("m", "no level, no clock"))
            )

        val parsed = NodeConsoleHistory.parse(payload, now = 1758000009999L)

        assertEquals(2, parsed.size)
        assertEquals("ERROR", parsed.first().l)
        assertEquals(ConsoleLineData.SRC_NODE, parsed.first().src)
        assertEquals("INFO", parsed.last().l)
        assertEquals(1758000009999L, parsed.last().t)
    }

    @Test
    fun `an empty or missing reply is no lines rather than a failure`() {
        assertTrue(NodeConsoleHistory.parse(null).isEmpty())
        assertTrue(NodeConsoleHistory.parse(JsonObject()).isEmpty())
        assertTrue(NodeConsoleHistory.parse(JsonObject().put("lines", JsonArray())).isEmpty())
    }

    @Test
    fun `picks the first failing line as the crash reason`() {
        val lines = listOf(
            node(10, "Starting Velocity"),
            node(20, "java.lang.UnsupportedClassVersionError: Velocity has been compiled by a more recent Java", "ERROR"),
            node(30, "Server process exited with code 1.", "ERROR")
        )

        assertEquals(
            "java.lang.UnsupportedClassVersionError: Velocity has been compiled by a more recent Java",
            ServerCrashReason.of(lines)
        )
    }

    @Test
    fun `finds a failure the process wrote to stdout and caps how much of it travels`() {
        val long = "Exception in thread \"main\" " + "x".repeat(400)

        assertEquals(ServerCrashReason.MAX_LENGTH, ServerCrashReason.of(listOf(node(10, long)))?.length)
        assertNull(ServerCrashReason.of(listOf(node(10, "Done (12.3s)! For help, type \"help\""))))

        // HotSpot's own start-up refusal goes to stdout as INFO with no colon; it still beats the
        // node's exit summary, which is never a reason on its own.
        assertEquals(
            "Error occurred during initialization of VM",
            ServerCrashReason.of(
                listOf(
                    node(11, "Error occurred during initialization of VM"),
                    node(12, "Too small maximum heap"),
                    node(13, "Server process exited with code 1.", "ERROR")
                )
            )
        )
        assertNull(ServerCrashReason.of(listOf(node(14, "Server process exited with code 137.", "ERROR"))))
        assertNull(ServerCrashReason.of(emptyList()))
        assertNull(ServerCrashReason.clean("   "))
        assertEquals("one line", ServerCrashReason.clean(" one\nline "))
    }
}
