package com.panomc.platform.server.console

import com.panomc.platform.server.dto.ConsoleLineData
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The console's Find on Pano's side (§2.4.20): the same rules the node and the plugin apply to their
 * log files, used on Pano's own buffer when no source can answer and on every source reply, because
 * an older peer ignores the query and sends the plain page.
 */
class ConsoleSearchTest {
    private fun line(m: String) = ConsoleLineData(0, "INFO", m)

    private fun lines(vararg messages: String) = messages.map { line(it) }

    @Test
    fun `a blank query is not a search`() {
        assertNull(ConsoleSearch.needle(null))
        assertNull(ConsoleSearch.needle(""))
        assertNull(ConsoleSearch.needle("   \t "))
    }

    @Test
    fun `a query is trimmed and cut at two hundred characters, never rejected`() {
        assertEquals("joined", ConsoleSearch.needle("  joined \n"))
        assertEquals(200, ConsoleSearch.MAX_QUERY_LENGTH)
        assertEquals("a".repeat(200), ConsoleSearch.needle("a".repeat(500)))
    }

    @Test
    fun `matching ignores case and treats the query as plain text`() {
        assertTrue(ConsoleSearch.matches(line("[10:00:00] [Server thread/INFO]: Steve JOINED"), "joined"))

        // The prefix is part of the text that is searched, exactly as it goes on the wire.
        assertTrue(ConsoleSearch.matches(line("[10:00:00] [Server thread/WARN]: Can't keep up!"), "/warn]"))

        // Pattern characters are only characters.
        assertFalse(ConsoleSearch.matches(line("price is 3x50"), "3.50"))
        assertTrue(ConsoleSearch.matches(line("tax [incl] (vat)"), "[incl] (vat"))
    }

    @Test
    fun `an old peer's unfiltered page is filtered on arrival`() {
        val reply = lines("Steve joined", "Saving chunks", "Alex joined", "Can't keep up!")

        assertEquals(listOf("Steve joined", "Alex joined"), ConsoleSearch.filter(reply, "JOINED").map { it.m })

        // And a page the source already filtered goes through unchanged.
        val filtered = ConsoleSearch.filter(reply, "joined")

        assertEquals(filtered, ConsoleSearch.filter(filtered, "joined"))
    }

    @Test
    fun `skip and limit count matches, newest page first, returned oldest first`() {
        // Every third line matches: hit 1, hit 4, ... hit 58.
        val buffer = (1..60).map { line(if (it % 3 == 1) "hit $it" else "miss $it") }

        val first = ConsoleSearch.page(buffer, 5, 0, "hit")

        assertEquals(listOf("hit 46", "hit 49", "hit 52", "hit 55", "hit 58"), first.lines.map { it.m })
        assertTrue(first.hasMore)

        val second = ConsoleSearch.page(buffer, 5, 5, "hit")

        assertEquals(listOf("hit 31", "hit 34", "hit 37", "hit 40", "hit 43"), second.lines.map { it.m })
    }

    @Test
    fun `hasMore means the page filled with lines still unscanned below it`() {
        val buffer = lines("hit one", "miss", "hit two", "hit three")

        assertTrue(ConsoleSearch.page(buffer, 2, 0, "hit").hasMore)
        assertFalse(ConsoleSearch.page(buffer, 5, 0, "hit").hasMore, "a short page is the end")

        // Filled on the oldest line there is: nothing left to scan.
        val last = ConsoleSearch.page(buffer, 1, 2, "hit")

        assertEquals(listOf("hit one"), last.lines.map { it.m })
        assertFalse(last.hasMore)

        val beyond = ConsoleSearch.page(buffer, 5, 10, "hit")

        assertTrue(beyond.lines.isEmpty())
        assertFalse(beyond.hasMore)
    }

    @Test
    fun `nothing to search is an empty page, not an error`() {
        assertEquals(ConsoleSearch.Page(emptyList(), false), ConsoleSearch.page(emptyList(), 500, 0, "x"))
        assertEquals(ConsoleSearch.Page(emptyList(), false), ConsoleSearch.page(lines("x"), 0, 0, "x"))
    }
}
