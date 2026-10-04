package com.panomc.node

import com.panomc.node.console.ConsoleLineParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * SGR → colour spans (§2.4.21 A).
 *
 * [VECTORS] is the table shared with the plugin's parser: the same inputs must give the same `m` and
 * the same `c` on both sides, because the panel draws whichever one it is handed and a server's
 * console must not change colour depending on which of the two relayed it. Keep it in step with
 * the plugin's copy.
 */
class ConsoleColorTest {
    /** One shared vector: raw line in, plain text and `[[start, end, color, flags], …]` out. */
    private data class Vector(val input: String, val m: String, val c: List<List<Any?>>)

    private fun span(start: Int, end: Int, color: String?, flags: Int) = listOf<Any?>(start, end, color, flags)

    private val VECTORS = listOf(
        // The five vectors agreed with the plugin.
        Vector("\u001b[31mred\u001b[0m plain", "red plain", listOf(span(0, 3, "#ff7b72", 0))),
        Vector(
            "\u001b[1;38;2;85;255;255mA\u001b[22mB\u001b[39mC",
            "ABC",
            listOf(span(0, 1, "#55ffff", 1), span(1, 2, "#55ffff", 0))
        ),
        Vector("\u001b[38;5;196mX", "X", listOf(span(0, 1, "#ff0000", 0))),
        Vector("\u001b[42mbg only", "bg only", emptyList()),
        Vector("\u001b[4;3mu", "u", listOf(span(0, 1, null, 6))),

        // Plain text has no spans at all.
        Vector("hello", "hello", emptyList()),
        // Bright 16-colour.
        Vector("\u001b[92mok", "ok", listOf(span(0, 2, "#aff5b4", 0))),
        // xterm-256: the palette, the cube's corners and middle, the greys.
        Vector("\u001b[38;5;9mx", "x", listOf(span(0, 1, "#ffa198", 0))),
        Vector("\u001b[38;5;16mx", "x", listOf(span(0, 1, "#000000", 0))),
        Vector("\u001b[38;5;21mx", "x", listOf(span(0, 1, "#0000ff", 0))),
        Vector("\u001b[38;5;231mx", "x", listOf(span(0, 1, "#ffffff", 0))),
        Vector("\u001b[38;5;244mx", "x", listOf(span(0, 1, "#808080", 0))),
        // Truecolour, lower-case hex.
        Vector("\u001b[38;2;255;128;0mx", "x", listOf(span(0, 1, "#ff8000", 0))),
        // Bold off keeps the colour; default foreground ends the span.
        Vector(
            "\u001b[1;33mwarn\u001b[22m!\u001b[39m.",
            "warn!.",
            listOf(span(0, 4, "#e3b341", 1), span(4, 5, "#e3b341", 0))
        ),
        // Italic and underline, on and off.
        Vector(
            "\u001b[3mit\u001b[23m \u001b[4mun\u001b[24m",
            "it un",
            listOf(span(0, 2, null, 2), span(3, 5, null, 4))
        ),
        // Flags accumulate across sequences; a colour change mid-way splits the span.
        Vector("\u001b[1m\u001b[3ma\u001b[31mb", "ab", listOf(span(0, 1, null, 3), span(1, 2, "#ff7b72", 3))),
        // `ESC[m` is a reset.
        Vector("\u001b[1;31ma\u001b[mb", "ab", listOf(span(0, 1, "#ff7b72", 1))),
        // An empty parameter is 0: bold, reset, red.
        Vector("\u001b[1;;31mx", "x", listOf(span(0, 1, "#ff7b72", 0))),
        // A background consumes its arguments and is ignored; the foreground after it applies.
        Vector("\u001b[48;2;1;2;3;32mg", "g", listOf(span(0, 1, "#7ee787", 0))),
        Vector("\u001b[48;5;21;49;103mx", "x", emptyList()),
        // Runs with the same style merge, across redundant codes and across stripped escapes.
        Vector("\u001b[31ma\u001b[31mb\u001b[1m\u001b[22mc", "abc", listOf(span(0, 3, "#ff7b72", 0))),
        Vector("\u001b[31ma\u001b]0;title\u0007b", "ab", listOf(span(0, 2, "#ff7b72", 0))),
        // Out of range: the arguments are consumed, nothing changes, the sequence carries on.
        Vector("\u001b[38;5;256;1mx", "x", listOf(span(0, 1, null, 1))),
        Vector("\u001b[31;38;2;300;0;0;4mx", "x", listOf(span(0, 1, "#ff7b72", 4))),
        // A missing argument or an unknown mode stops the sequence; what came before it counts.
        Vector("\u001b[1;38;5mx", "x", listOf(span(0, 1, null, 1))),
        Vector("\u001b[4;38;2;1;2mx", "x", listOf(span(0, 1, null, 4))),
        Vector("\u001b[38;7;31mx", "x", emptyList()),
        Vector("\u001b[3;38mx", "x", listOf(span(0, 1, null, 2))),
        // Colon sub-parameters and private CSIs are stripped with no change of style.
        Vector("\u001b[31m\u001b[38:2::0:0:255mx", "x", listOf(span(0, 1, "#ff7b72", 0))),
        Vector("\u001b[?25hvisible", "visible", emptyList()),
        // A number too long to be one is 0, which is a reset.
        Vector("\u001b[31m\u001b[99999999999mx", "x", emptyList())
    )

    private fun spansOf(raw: String) = ConsoleLineParser.styled(raw).spans.map { span(it.start, it.end, it.color, it.flags) }

    @Test
    fun `every shared vector gives the agreed text and spans`() {
        VECTORS.forEach { vector ->
            val styled = ConsoleLineParser.styled(vector.input)

            assertEquals(vector.m, styled.text, "m of ${vector.input.replace("\u001b", "ESC")}")
            assertEquals(vector.c, spansOf(vector.input), "c of ${vector.input.replace("\u001b", "ESC")}")
        }
    }

    @Test
    fun `the plain text is exactly what stripping always produced`() {
        // m is not allowed to change because colour exists: search, copy and filters read it.
        VECTORS.forEach { vector ->
            assertEquals(ConsoleLineParser.stripAnsi(vector.input), ConsoleLineParser.styled(vector.input).text)
        }

        // Including an escape too broken to be one, which stays in the text as it always has.
        assertEquals("\u001b[31", ConsoleLineParser.styled("\u001b[31").text)
        assertTrue(ConsoleLineParser.styled("\u001b[31").spans.isEmpty())
    }

    @Test
    fun `the whole 16-colour palette maps in order`() {
        (30..37).forEachIndexed { index, code ->
            assertEquals(ConsoleLineParser.PALETTE[index], ConsoleLineParser.styled("\u001b[${code}mx").spans.single().color)
        }

        (90..97).forEachIndexed { index, code ->
            assertEquals(ConsoleLineParser.PALETTE[8 + index], ConsoleLineParser.styled("\u001b[${code}mx").spans.single().color)
        }

        // xterm 0–15 are the same sixteen, in the same order.
        (0..15).forEach { assertEquals(ConsoleLineParser.PALETTE[it], ConsoleLineParser.xterm256(it)) }

        ConsoleLineParser.PALETTE.forEach { assertTrue(Regex("^#[0-9a-f]{6}$").matches(it), it) }
    }

    @Test
    fun `no more than sixty-four spans, the first ones kept`() {
        // Alternating colours so nothing merges: seventy runs of one character.
        val raw = (0 until 70).joinToString("") { index -> "\u001b[${if (index % 2 == 0) 31 else 32}m${'a' + (index % 26)}" }

        val styled = ConsoleLineParser.styled(raw)

        assertEquals(70, styled.text.length, "the text is never cut for the spans' sake")
        assertEquals(ConsoleLineParser.MAX_SPANS, styled.spans.size)
        assertEquals(listOf(63, 64), styled.spans.last().let { listOf(it.start, it.end) })
    }

    @Test
    fun `spans are clipped to the capped text`() {
        val long = ConsoleLineParser.styled("\u001b[31m" + "x".repeat(5000))

        assertEquals(ConsoleLineParser.MAX_MESSAGE_LENGTH, long.text.length)
        assertEquals(span(0, ConsoleLineParser.MAX_MESSAGE_LENGTH, "#ff7b72", 0), spansOf("\u001b[31m" + "x".repeat(5000)).single())

        // A span crossing the cap ends at it; one starting past it does not exist.
        val crossing = spansOf("x".repeat(4090) + "\u001b[32m" + "y".repeat(10))

        assertEquals(listOf(span(4090, 4096, "#7ee787", 0)), crossing)
        assertTrue(spansOf("x".repeat(4096) + "\u001b[31mz").isEmpty())
    }

    @Test
    fun `spans are sorted, non-overlapping and never empty`() {
        VECTORS.forEach { vector ->
            val spans = ConsoleLineParser.styled(vector.input).spans

            spans.forEach { assertTrue(it.start < it.end && it.end <= vector.m.length) }
            spans.zipWithNext().forEach { (first, second) -> assertTrue(first.end <= second.start) }
        }
    }

    @Test
    fun `the file form keeps only the colour codes and parses back to the same line`() {
        VECTORS.forEach { vector ->
            val styled = ConsoleLineParser.styled(vector.input)
            val again = ConsoleLineParser.styled(styled.fileForm)

            assertEquals(styled.text, again.text)
            assertEquals(styled.spans, again.spans)
        }

        // The title escape and the cursor mode are gone, the SGR codes are kept.
        assertEquals("\u001b[31ma" + "b", ConsoleLineParser.styled("\u001b[31ma\u001b]0;title\u0007b").fileForm)
        assertEquals("visible", ConsoleLineParser.styled("\u001b[?25hvisible").fileForm)
    }

    @Test
    fun `a line only carries c when it has colour`() {
        val plain = ConsoleLineParser.toLine("[10:00:00] [Server thread/INFO]: Done", 1)

        assertNull(plain.c)

        val coloured = ConsoleLineParser.toLine("[10:00:00] [Server thread/WARN]: \u001b[33mCareful", 1)

        assertEquals("WARN", coloured.l, "the level is read the same way as before")
        assertEquals("[10:00:00] [Server thread/WARN]: Careful", coloured.m)
        assertEquals(1, coloured.c?.size)
        assertFalse(coloured.c!!.single().color.isNullOrEmpty())
    }
}
