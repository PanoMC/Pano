package com.panomc.platform.server.console

import com.google.gson.Gson
import com.panomc.platform.node.event.request.ServerConsoleLinesEventRequest
import com.panomc.platform.server.dto.ConsoleLineData
import com.panomc.platform.server.dto.ConsoleSpan
import com.panomc.platform.server.event.request.ConsoleHistoryResultEventRequest
import com.panomc.platform.server.event.request.ConsoleLinesEventRequest
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * What Pano accepts as colour on a console line, and what it relays (§2.4.21 D).
 *
 * The spans come from peers and end up in the panel's rendering code, so every one of them is
 * judged again here: a bad span costs that span, never the line, and a malformed `c` never costs
 * the batch it arrived in.
 */
class ConsoleSpansTest {
    private val message = "Steve joined the game"

    private fun spans(vararg entries: List<Any?>) = ConsoleSpans.parse(entries.toList())

    @Test
    fun `a well-formed span is kept as sent`() {
        val kept = ConsoleSpans.validate(spans(listOf(0, 5, "#ff7b72", 1), listOf(6, 12, null, 6)), message)

        assertEquals(listOf(ConsoleSpan(0, 5, "#ff7b72", 1), ConsoleSpan(6, 12, null, 6)), kept)
    }

    @Test
    fun `offsets must be integers inside the line`() {
        assertNull(ConsoleSpans.validate(spans(listOf(5, 5, "#ff7b72", 0)), message), "empty")
        assertNull(ConsoleSpans.validate(spans(listOf(6, 5, "#ff7b72", 0)), message), "backwards")
        assertNull(ConsoleSpans.validate(spans(listOf(-1, 5, "#ff7b72", 0)), message), "negative")
        assertNull(ConsoleSpans.validate(spans(listOf(0, message.length + 1, "#ff7b72", 0)), message), "past the end")
        assertNull(ConsoleSpans.validate(spans(listOf(0.5, 5, "#ff7b72", 0)), message), "not an integer")
        assertNull(ConsoleSpans.validate(spans(listOf("0", 5, "#ff7b72", 0)), message), "a string")
        assertNull(ConsoleSpans.validate(spans(listOf(0, 1e12, "#ff7b72", 0)), message), "out of Int range")

        // Up to the very end is fine.
        assertEquals(1, ConsoleSpans.validate(spans(listOf(0, message.length, "#ff7b72", 0)), message)?.size)
    }

    @Test
    fun `a colour is null or exactly lower-case #rrggbb, never anything that could reach a style`() {
        listOf("#FF7B72", "red", "#fff", "#ff7b72;background:url(x)", "rgb(1,2,3)", "#ff7b7g", "", " #ff7b72")
            .forEach { colour ->
                assertNull(ConsoleSpans.validate(spans(listOf(0, 5, colour, 0)), message), colour)
            }

        // A number where the colour goes is not a colour: the entry is dropped.
        assertTrue(ConsoleSpans.parse(listOf(listOf(0, 5, 12, 0)))!!.isEmpty())
    }

    @Test
    fun `flags are 0 to 7`() {
        assertNull(ConsoleSpans.validate(spans(listOf(0, 5, null, 8)), message))
        assertNull(ConsoleSpans.validate(spans(listOf(0, 5, null, -1)), message))

        (0..7).forEach { flags -> assertEquals(1, ConsoleSpans.validate(spans(listOf(0, 5, null, flags)), message)?.size) }
    }

    @Test
    fun `an overlapping or out-of-order span is dropped, the rest kept`() {
        val kept = ConsoleSpans.validate(
            spans(listOf(0, 5, "#ff7b72", 0), listOf(3, 8, "#7ee787", 0), listOf(6, 12, "#79c0ff", 0), listOf(1, 2, null, 1)),
            message
        )

        assertEquals(listOf(ConsoleSpan(0, 5, "#ff7b72", 0), ConsoleSpan(6, 12, "#79c0ff", 0)), kept)
    }

    @Test
    fun `entries of the wrong shape are dropped one by one`() {
        val kept = ConsoleSpans.validate(
            ConsoleSpans.parse(
                listOf(
                    listOf(0, 1, "#ff7b72"),
                    listOf(0, 1, "#ff7b72", 0, "extra"),
                    "not an array",
                    mapOf("start" to 0),
                    null,
                    listOf(2, 3, "#ff7b72", 0)
                )
            ),
            message
        )

        assertEquals(listOf(ConsoleSpan(2, 3, "#ff7b72", 0)), kept)
    }

    @Test
    fun `no more than sixty-four spans are relayed`() {
        val many = (0 until 70).map { listOf<Any?>(it, it + 1, "#ff7b72", 0) }

        val kept = ConsoleSpans.validate(ConsoleSpans.parse(many), "x".repeat(100))

        assertEquals(ConsoleSpans.MAX_SPANS, kept?.size)
        assertEquals(ConsoleSpan(63, 64, "#ff7b72", 0), kept?.last())
    }

    @Test
    fun `a c that is not a list is no colour at all`() {
        assertNull(ConsoleSpans.parse("x"))
        assertNull(ConsoleSpans.parse(42))
        assertNull(ConsoleSpans.parse(JsonObject().put("a", 1)))
        assertNull(ConsoleSpans.validate(null, message))
        assertNull(ConsoleSpans.validate(emptyList(), message))
    }

    @Test
    fun `a malformed c never fails the batch it arrived in`() {
        val batch = """
            {"lines":[
              {"t":1,"l":"INFO","m":"plain"},
              {"t":2,"l":"INFO","m":"coloured","c":[[0,8,"#ff7b72",1]]},
              {"t":3,"l":"INFO","m":"string c","c":"red"},
              {"t":4,"l":"INFO","m":"object c","c":{"a":1}},
              {"t":5,"l":"INFO","m":"bad entry","c":[[0,"3","#fff",0],[0,3,"#7ee787",0]]},
              {"t":6,"l":"INFO","m":"null c","c":null}
            ]}
        """.trimIndent()

        listOf(ConsoleLinesEventRequest::class.java, ServerConsoleLinesEventRequest::class.java).forEach { type ->
            val decoded = Gson().fromJson(batch, type)

            val lines = when (decoded) {
                is ConsoleLinesEventRequest -> decoded.lines
                is ServerConsoleLinesEventRequest -> decoded.lines
                else -> null
            }!!

            assertEquals(6, lines.size, "every line survives decoding")
            assertNull(lines[0].c)
            assertEquals(listOf(ConsoleSpan(0, 8, "#ff7b72", 1)), lines[1].c)
            assertNull(lines[2].c)
            assertNull(lines[3].c)
            assertEquals(listOf(ConsoleSpan(0, 3, "#7ee787", 0)), lines[4].c)
            assertNull(lines[5].c)
        }
    }

    @Test
    fun `a node's history reply keeps only the spans valid for each line`() {
        val payload = JsonObject().put(
            "lines",
            JsonArray()
                .add(
                    JsonObject().put("t", 1).put("l", "INFO").put("m", "red")
                        .put("c", JsonArray().add(JsonArray().add(0).add(3).add("#ff7b72").add(0)))
                )
                .add(
                    JsonObject().put("t", 2).put("l", "INFO").put("m", "short")
                        .put("c", JsonArray().add(JsonArray().add(0).add(99).add("#ff7b72").add(0)))
                )
                .add(JsonObject().put("t", 3).put("l", "INFO").put("m", "old peer"))
        )

        val lines = NodeConsoleHistory.parse(payload)

        assertEquals(listOf(ConsoleSpan(0, 3, "#ff7b72", 0)), lines[0].c)
        assertNull(lines[1].c, "a span past the end of its line is dropped")
        assertNull(lines[2].c, "a line from a peer that predates colour simply has none")
    }

    @Test
    fun `a plugin's history reply is judged the same way`() {
        val reply = Gson().fromJson(
            """{"lines":[{"t":1,"l":"INFO","m":"ab","c":[[0,2,"#7ee787",4],[0,1,"#ff7b72",0]]}],"hasMore":false}""",
            ConsoleHistoryResultEventRequest::class.java
        )

        assertEquals(listOf(ConsoleSpan(0, 2, "#7ee787", 4)), NodeConsoleHistory.parsePlugin(reply.lines).single().c)
    }

    @Test
    fun `the panel gets c only when there is colour, and the default colour as null`() {
        val plain = ConsoleLineData(1, "INFO", "plain", ConsoleLineData.SRC_NODE).toJsonObject()

        assertEquals(setOf("t", "l", "m", "src"), plain.fieldNames(), "a plain line is what it always was")

        val coloured = ConsoleLineData(
            1, "INFO", "ab", ConsoleLineData.SRC_NODE,
            listOf(ConsoleSpan(0, 1, "#ff7b72", 1), ConsoleSpan(1, 2, null, 6))
        ).toJsonObject()

        assertEquals("""[[0,1,"#ff7b72",1],[1,2,null,6]]""", coloured.getJsonArray("c").encode())
    }

    @Test
    fun `a search keeps a matching line whole, colours included`() {
        val line = ConsoleLineData(1, "INFO", "Steve joined", ConsoleLineData.SRC_NODE, listOf(ConsoleSpan(0, 5, "#ff7b72", 0)))

        assertEquals(listOf(line), ConsoleSearch.filter(listOf(line), "joined"))
        assertFalse(ConsoleSearch.page(listOf(line), 10, 0, "steve").lines.single().c.isNullOrEmpty())
    }
}
