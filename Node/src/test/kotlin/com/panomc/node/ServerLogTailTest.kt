package com.panomc.node

import com.panomc.node.console.ConsoleHistoryMerge
import com.panomc.node.console.ConsoleLine
import com.panomc.node.console.ServerLogTail
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.zip.GZIPOutputStream

class ServerLogTailTest {
    @TempDir
    lateinit var root: File

    private val zone: ZoneId = ZoneId.of("UTC")

    private fun logs(): File = File(root, "logs").apply { mkdirs() }

    private fun writeLatest(vararg lines: String): File =
        File(logs(), "latest.log").apply { writeText(lines.joinToString("\n") + "\n") }

    private fun writeRotated(name: String, vararg lines: String): File {
        val file = File(logs(), name)

        GZIPOutputStream(file.outputStream()).use { it.write((lines.joinToString("\n") + "\n").toByteArray()) }

        return file
    }

    private fun stamp(date: LocalDate, hour: Int, minute: Int, second: Int): Long =
        LocalDateTime.of(date.year, date.monthValue, date.dayOfMonth, hour, minute, second)
            .atZone(zone).toInstant().toEpochMilli()

    @Test
    fun `missing logs directory is not an error`() {
        assertEquals(emptyList<ConsoleLine>(), ServerLogTail.read(root, 500, zone = zone).lines)
        assertEquals(emptyList<ConsoleLine>(), ServerLogTail.read(File(root, "nowhere"), 500, zone = zone).lines)
    }

    @Test
    fun `reads a file shorter than the limit whole`() {
        writeLatest(
            "[10:00:00] [Server thread/INFO]: Starting minecraft server",
            "[10:00:01] [Server thread/WARN]: Something odd",
            "[10:00:02] [Server thread/ERROR]: Broke"
        )

        val lines = ServerLogTail.read(root, 500, zone = zone).lines

        assertEquals(3, lines.size)
        assertEquals(listOf("INFO", "WARN", "ERROR"), lines.map { it.l })
        assertTrue(lines.first().m.endsWith("Starting minecraft server"))
        assertTrue(lines.last().m.endsWith("Broke"))
    }

    @Test
    fun `keeps only the newest lines when the file is longer than the limit`() {
        writeLatest(*(1..50).map { "[10:00:00] [Server thread/INFO]: line $it" }.toTypedArray())

        val lines = ServerLogTail.read(root, 10, zone = zone).lines

        assertEquals(10, lines.size)
        assertTrue(lines.first().m.endsWith("line 41"))
        assertTrue(lines.last().m.endsWith("line 50"))
    }

    @Test
    fun `ignores the blank line at the end of a file`() {
        writeLatest("[10:00:00] [Server thread/INFO]: only line")

        assertEquals(1, ServerLogTail.read(root, 500, zone = zone).lines.size)
    }

    @Test
    fun `continues into a rotated gzip when latest is short`() {
        val yesterday = LocalDate.now(zone).minusDays(1)

        writeRotated(
            "$yesterday-1.log.gz",
            "[09:00:00] [Server thread/INFO]: old one",
            "[09:00:01] [Server thread/INFO]: old two"
        )

        writeLatest("[10:00:00] [Server thread/INFO]: new one")

        val lines = ServerLogTail.read(root, 500, zone = zone).lines

        assertEquals(listOf("old one", "old two", "new one"), lines.map { it.m.substringAfterLast(": ") })
        assertTrue(lines[0].t < lines[2].t)
    }

    @Test
    fun `stops at the file budget instead of reading every rotation`() {
        (1..6).forEach { index ->
            writeRotated("2025-08-1$index-1.log.gz", "[09:00:00] [Server thread/INFO]: file $index")
        }

        val lines = ServerLogTail.read(root, 500, zone = zone).lines

        assertEquals(ServerLogTail.MAX_ROTATED_FILES, lines.size)
        assertEquals(listOf("file 4", "file 5", "file 6"), lines.map { it.m.substringAfterLast(": ") })
    }

    @Test
    fun `cuts the byte window at a line boundary`() {
        val file = File(logs(), "latest.log")
        val filler = "x".repeat(1024)

        // Comfortably more than the tail window, so the window starts mid-line every time.
        val count = (ServerLogTail.TAIL_WINDOW_BYTES / filler.length).toInt() + 200

        file.bufferedWriter().use { writer ->
            (1..count).forEach { writer.write("[10:00:00] [Server thread/INFO]: $it $filler\n") }
        }

        assertTrue(file.length() > ServerLogTail.TAIL_WINDOW_BYTES)

        val lines = ServerLogTail.read(root, 200, zone = zone).lines

        assertEquals(200, lines.size)

        // Every line is whole: no fragment of the line the window opened in the middle of.
        lines.forEach { assertTrue(it.m.startsWith("[10:00:00] [Server thread/INFO]: "), "cut line: ${it.m.take(40)}") }

        assertTrue(lines.last().m.startsWith("[10:00:00] [Server thread/INFO]: $count "))
    }

    @Test
    fun `truncates an absurdly long line instead of keeping it whole`() {
        writeLatest("[10:00:00] [Server thread/INFO]: " + "y".repeat(64 * 1024))

        val lines = ServerLogTail.read(root, 10, zone = zone).lines

        assertEquals(1, lines.size)
        assertEquals(ServerLogTail.MAX_LINE_LENGTH, lines.first().m.length)
    }

    @Test
    fun `dates lines from the rotated file name`() {
        writeRotated("2025-08-16-14.log.gz", "[02:29:00] [Server thread/INFO]: hello")

        val lines = ServerLogTail.read(root, 10, zone = zone).lines

        assertEquals(stamp(LocalDate.of(2025, 8, 16), 2, 29, 0), lines.single().t)
    }

    @Test
    fun `dates lines from the file mtime when the name has no date`() {
        val file = writeLatest("[02:29:00] [Server thread/INFO]: hello")
        val day = LocalDate.of(2025, 3, 4)

        file.setLastModified(stamp(day, 12, 0, 0))

        assertEquals(stamp(day, 2, 29, 0), ServerLogTail.read(root, 10, zone = zone).lines.single().t)
    }

    @Test
    fun `rolls the day forward when the clock goes backwards`() {
        val day = LocalDate.of(2025, 8, 16)

        writeRotated(
            "$day-1.log.gz",
            "[23:59:58] [Server thread/INFO]: before",
            "[23:59:59] [Server thread/INFO]: still before",
            "[00:00:01] [Server thread/INFO]: after",
            "[00:00:02] [Server thread/INFO]: later"
        )

        val lines = ServerLogTail.read(root, 10, zone = zone).lines

        assertEquals(stamp(day, 23, 59, 58), lines[0].t)
        assertEquals(stamp(day.plusDays(1), 0, 0, 1), lines[2].t)
        assertEquals(stamp(day.plusDays(1), 0, 0, 2), lines[3].t)
    }

    @Test
    fun `a line with no time inherits the one before it`() {
        val day = LocalDate.of(2025, 8, 16)

        writeRotated(
            "$day-1.log.gz",
            "java.lang.RuntimeException: no timestamp here",
            "[10:00:00] [Server thread/ERROR]: boom",
            "\tat com.example.Thing.run(Thing.java:1)"
        )

        val lines = ServerLogTail.read(root, 10, zone = zone).lines

        // The first line has nothing to inherit, so it takes the file's day at midnight.
        assertEquals(stamp(day, 0, 0, 0), lines[0].t)
        assertEquals(stamp(day, 10, 0, 0), lines[1].t)
        assertEquals(lines[1].t, lines[2].t)
    }

    @Test
    fun `dates latest log backwards from its mtime when it spans midnight`() {
        val file = writeLatest(
            "[23:59:58] [Server thread/INFO]: before midnight",
            "[00:00:02] [Server thread/INFO]: after midnight"
        )

        val today = LocalDate.of(2025, 3, 4)

        file.setLastModified(stamp(today, 0, 30, 0))

        val lines = ServerLogTail.read(root, 10, zone = zone).lines

        assertEquals(stamp(today.minusDays(1), 23, 59, 58), lines[0].t)
        assertEquals(stamp(today, 0, 0, 2), lines[1].t)
    }

    @Test
    fun `steps over the newest lines when asked for an older page`() {
        writeLatest(*(1..50).map { "[10:00:00] [Server thread/INFO]: line $it" }.toTypedArray())

        val page = ServerLogTail.read(root, 10, skip = 10, zone = zone)

        assertEquals(10, page.lines.size)
        assertTrue(page.lines.first().m.endsWith("line 31"))
        assertTrue(page.lines.last().m.endsWith("line 40"))
        assertTrue(page.hasMore)
    }

    @Test
    fun `says there is more only while older lines are left`() {
        writeLatest(*(1..20).map { "[10:00:00] [Server thread/INFO]: line $it" }.toTypedArray())

        assertTrue(ServerLogTail.read(root, 5, zone = zone).hasMore)
        assertTrue(ServerLogTail.read(root, 5, skip = 10, zone = zone).hasMore)

        // The page that ends on the first line the file holds is the last page there is.
        val last = ServerLogTail.read(root, 5, skip = 15, zone = zone)

        assertTrue(last.lines.first().m.endsWith("line 1"))
        assertFalse(last.hasMore)

        // And so is a page that was never full in the first place.
        assertFalse(ServerLogTail.read(root, 500, zone = zone).hasMore)
    }

    @Test
    fun `a page past the beginning of the history is empty rather than the beginning again`() {
        writeLatest(*(1..5).map { "[10:00:00] [Server thread/INFO]: line $it" }.toTypedArray())

        val page = ServerLogTail.read(root, 10, skip = 10, zone = zone)

        assertTrue(page.lines.isEmpty())
        assertFalse(page.hasMore)
    }

    @Test
    fun `pages out of latest and into a rotated file`() {
        writeRotated(
            "2025-08-16-1.log.gz",
            "[09:00:00] [Server thread/INFO]: old one",
            "[09:00:01] [Server thread/INFO]: old two",
            "[09:00:02] [Server thread/INFO]: old three"
        )

        writeLatest(
            "[10:00:00] [Server thread/INFO]: new one",
            "[10:00:01] [Server thread/INFO]: new two"
        )

        val second = ServerLogTail.read(root, 2, skip = 2, zone = zone)

        assertEquals(listOf("old two", "old three"), second.lines.map { it.m.substringAfterLast(": ") })
        assertTrue(second.hasMore)

        val third = ServerLogTail.read(root, 2, skip = 4, zone = zone)

        assertEquals(listOf("old one"), third.lines.map { it.m.substringAfterLast(": ") })
        assertFalse(third.hasMore)
    }

    @Test
    fun `a full page taken before the rotations ran out still has more`() {
        (1..6).forEach { index ->
            writeRotated("2025-08-1$index-1.log.gz", "[09:00:00] [Server thread/INFO]: file $index")
        }

        val page = ServerLogTail.read(root, 2, zone = zone)

        assertEquals(listOf("file 5", "file 6"), page.lines.map { it.m.substringAfterLast(": ") })
        assertTrue(page.hasMore)
    }

    @Test
    fun `a blank query is the plain page and not a search`() {
        writeLatest(*(1..50).map { "[10:00:00] [Server thread/INFO]: line $it" }.toTypedArray())

        val plain = ServerLogTail.read(root, 10, skip = 10, zone = zone)

        // Byte for byte the same answer, whatever the empty Find box happened to contain.
        assertEquals(plain, ServerLogTail.read(root, 10, skip = 10, query = null, zone = zone))
        assertEquals(plain, ServerLogTail.read(root, 10, skip = 10, query = "", zone = zone))
        assertEquals(plain, ServerLogTail.read(root, 10, skip = 10, query = "   ", zone = zone))
    }

    @Test
    fun `a search keeps only matching lines, oldest first`() {
        writeLatest(
            "[10:00:00] [Server thread/INFO]: Steve joined the game",
            "[10:00:01] [Server thread/INFO]: Saving chunks",
            "[10:00:02] [Server thread/INFO]: Alex joined the game",
            "[10:00:03] [Server thread/WARN]: Can't keep up!",
            "[10:00:04] [Server thread/INFO]: Steve left the game"
        )

        val page = ServerLogTail.read(root, 10, query = "joined", zone = zone)

        assertEquals(listOf("Steve joined the game", "Alex joined the game"), page.lines.map { it.m.substringAfterLast(": ") })
        assertFalse(page.hasMore)
    }

    @Test
    fun `a search ignores case and is a plain substring rather than a pattern`() {
        writeLatest(
            "[10:00:00] [Server thread/INFO]: Steve JOINED the game",
            "[10:00:01] [Server thread/INFO]: price is 3.50 (tax [incl])",
            "[10:00:02] [Server thread/INFO]: price is 3x50"
        )

        assertEquals(1, ServerLogTail.read(root, 10, query = "joined", zone = zone).lines.size)

        // Regex metacharacters are just characters: "3.50" does not match "3x50", and a bracket
        // that would be an unterminated class in a pattern is simply looked for.
        assertEquals(1, ServerLogTail.read(root, 10, query = "3.50", zone = zone).lines.size)
        assertEquals(1, ServerLogTail.read(root, 10, query = "[incl]", zone = zone).lines.size)
    }

    @Test
    fun `skip and limit count matches, not lines`() {
        // Every third line matches: 1, 4, 7, ... 58 — twenty matches among sixty lines.
        writeLatest(*(1..60).map { index ->
            if (index % 3 == 1) "[10:00:00] [Server thread/INFO]: hit $index" else "[10:00:00] [Server thread/INFO]: miss $index"
        }.toTypedArray())

        val first = ServerLogTail.read(root, 5, query = "hit", zone = zone)

        assertEquals(listOf("hit 46", "hit 49", "hit 52", "hit 55", "hit 58"), first.lines.map { it.m.substringAfterLast(": ") })
        assertTrue(first.hasMore)

        // The next page steps over the five matches already shown, not five lines.
        val second = ServerLogTail.read(root, 5, skip = 5, query = "hit", zone = zone)

        assertEquals(listOf("hit 31", "hit 34", "hit 37", "hit 40", "hit 43"), second.lines.map { it.m.substringAfterLast(": ") })
    }

    @Test
    fun `hasMore is only true when the page filled with window left to scan`() {
        writeLatest(
            "[10:00:00] [Server thread/INFO]: hit one",
            "[10:00:01] [Server thread/INFO]: miss",
            "[10:00:02] [Server thread/INFO]: hit two",
            "[10:00:03] [Server thread/INFO]: hit three"
        )

        // Full page, older lines still unscanned below it.
        assertTrue(ServerLogTail.read(root, 2, query = "hit", zone = zone).hasMore)

        // Short page: the window ran out first.
        assertFalse(ServerLogTail.read(root, 5, query = "hit", zone = zone).hasMore)

        // A page that fills on the very oldest line of the window has nothing left below it.
        val last = ServerLogTail.read(root, 1, skip = 2, query = "hit", zone = zone)

        assertEquals(listOf("hit one"), last.lines.map { it.m.substringAfterLast(": ") })
        assertFalse(last.hasMore)

        // Past the last match is empty, not an error.
        val beyond = ServerLogTail.read(root, 5, skip = 10, query = "hit", zone = zone)

        assertTrue(beyond.lines.isEmpty())
        assertFalse(beyond.hasMore)
    }

    @Test
    fun `a search never reaches further back than the deepest unfiltered page`() {
        val total = ServerLogTail.SEARCH_WINDOW_LINES + 50

        // Only the fifty lines older than the window say "needle"; everything inside it does not,
        // except the single oldest line still inside it, which carries its own marker.
        writeLatest(*(1..total).map { index ->
            when {
                index <= 50 -> "[10:00:00] needle $index"
                index == 51 -> "[10:00:00] edge-of-window"
                else -> "[10:00:00] hay $index"
            }
        }.toTypedArray())

        val page = ServerLogTail.read(root, 100, query = "needle", zone = zone)

        assertTrue(page.lines.isEmpty(), "lines older than the window are out of reach")
        assertFalse(page.hasMore)

        // And the window is exactly the lines "Load older" could show: the oldest one still in
        // reach of an unfiltered page is the oldest one a search can find.
        val deepest = ServerLogTail.read(root, ServerLogTail.MAX_LIMIT, skip = ServerLogTail.MAX_SKIP, zone = zone)

        assertEquals("edge-of-window", deepest.lines.first().m.substringAfterLast("] "))

        val edge = ServerLogTail.read(root, 10, query = "edge-of-window", zone = zone)

        assertEquals(1, edge.lines.size)
        assertFalse(edge.hasMore, "the match was the oldest line in the window")
    }

    @Test
    fun `a search follows paging into the rotated files`() {
        writeRotated(
            "2025-08-16-1.log.gz",
            "[09:00:00] [Server thread/INFO]: old hit",
            "[09:00:01] [Server thread/INFO]: old miss"
        )

        writeLatest(
            "[10:00:00] [Server thread/INFO]: new hit",
            "[10:00:01] [Server thread/INFO]: new miss"
        )

        val page = ServerLogTail.read(root, 10, query = "HIT", zone = zone)

        assertEquals(listOf("old hit", "new hit"), page.lines.map { it.m.substringAfterLast(": ") })
    }

    @Test
    fun `the query is trimmed and capped at two hundred characters`() {
        assertEquals(null, ServerLogTail.searchNeedle(null))
        assertEquals(null, ServerLogTail.searchNeedle("   "))
        assertEquals("joined", ServerLogTail.searchNeedle("  joined  "))

        val long = "a".repeat(500)

        assertEquals(ServerLogTail.MAX_QUERY_LENGTH, ServerLogTail.searchNeedle(long)!!.length)
        assertEquals(200, ServerLogTail.MAX_QUERY_LENGTH)

        // A line that contains the first two hundred characters of a longer query matches it.
        writeLatest("[10:00:00] ${"a".repeat(250)}")

        assertEquals(1, ServerLogTail.read(root, 10, query = "a".repeat(300), zone = zone).lines.size)
    }

    @Test
    fun `the ring can be searched with the same rules when there are no files`() {
        val ring = (1..10).map { ConsoleLine(it.toLong(), "INFO", if (it % 2 == 0) "even $it" else "odd $it") }

        val page = ServerLogTail.searchLines(ring, 2, 1, "EVEN")

        assertEquals(listOf("even 6", "even 8"), page.lines.map { it.m })
        assertTrue(page.hasMore)
    }

    private fun writeConsoleLog(vararg records: String): File =
        File(root, ".pano-node/console.log").apply {
            parentFile.mkdirs()
            writeText(records.joinToString("\n") + "\n")
        }

    private fun writeConsoleRotation(index: Int, vararg records: String): File =
        File(root, ".pano-node/console.log.$index").apply {
            parentFile.mkdirs()
            writeText(records.joinToString("\n") + "\n")
        }

    @Test
    fun `the node's console log is preferred over the server's own logs`() {
        writeLatest("[10:00:00] [Server thread/INFO]: from latest.log")

        writeConsoleLog(
            "1000\t[10:00:00] [Server thread/INFO]: \u001b[32mDone\u001b[0m (3s)",
            "2000\t[Pano:admin] > say hi"
        )

        val lines = ServerLogTail.read(root, 10, zone = zone).lines

        assertEquals(listOf("[10:00:00] [Server thread/INFO]: Done (3s)", "[Pano:admin] > say hi"), lines.map { it.m })

        // The time is the one the node stamped, not one guessed from the text.
        assertEquals(listOf(1000L, 2000L), lines.map { it.t })

        // The colours come back out of the kept codes, exactly as they were live.
        val done = lines.first()

        assertEquals(1, done.c?.size)
        assertEquals("#7ee787", done.c!!.single().color)
        assertEquals("Done", done.m.substring(done.c!!.single().start, done.c!!.single().end))
        assertEquals(null, lines.last().c)
    }

    @Test
    fun `without a console log the server's logs are read exactly as before`() {
        writeLatest("[10:00:00] [Server thread/INFO]: from latest.log")

        val lines = ServerLogTail.read(root, 10, zone = zone).lines

        assertEquals(listOf("[10:00:00] [Server thread/INFO]: from latest.log"), lines.map { it.m })
        assertEquals(null, lines.single().c)
    }

    @Test
    fun `the console log pages into its rotations with the same rules`() {
        writeConsoleRotation(2, "1\toldest one", "2\toldest two")
        writeConsoleRotation(1, "3\told one", "4\told two")
        writeConsoleLog("5\tnew one", "6\tnew two")

        val first = ServerLogTail.read(root, 2, zone = zone)

        assertEquals(listOf("new one", "new two"), first.lines.map { it.m })
        assertTrue(first.hasMore)

        val second = ServerLogTail.read(root, 2, skip = 2, zone = zone)

        assertEquals(listOf("old one", "old two"), second.lines.map { it.m })
        assertTrue(second.hasMore)

        val third = ServerLogTail.read(root, 2, skip = 4, zone = zone)

        assertEquals(listOf("oldest one", "oldest two"), third.lines.map { it.m })
        assertFalse(third.hasMore)
    }

    @Test
    fun `a search reads the console log and matches the plain text, not the codes`() {
        writeConsoleLog(
            "1\t\u001b[31mSteve\u001b[0m joined the game",
            "2\tSaving chunks",
            "3\tAlex joined the game"
        )

        val page = ServerLogTail.read(root, 10, query = "steve joined", zone = zone)

        assertEquals(listOf("Steve joined the game"), page.lines.map { it.m })
        assertEquals("#ff7b72", page.lines.single().c!!.single().color)

        // The codes themselves are not text: "31m" is found nowhere.
        assertTrue(ServerLogTail.read(root, 10, query = "31m", zone = zone).lines.isEmpty())
    }

    @Test
    fun `a record with no readable time keeps the one before it`() {
        writeConsoleLog("5000\tfirst", "no tab at all", "not-a-number\tthird")

        val lines = ServerLogTail.read(root, 10, zone = zone).lines

        assertEquals(listOf(5000L, 5000L, 5000L), lines.map { it.t })
        assertEquals(listOf("first", "no tab at all", "not-a-number\tthird"), lines.map { it.m })
    }

    @Test
    fun `a console log counts as history`() {
        assertFalse(ServerLogTail.hasHistory(root))

        writeConsoleLog("1\thello")

        assertTrue(ServerLogTail.hasHistory(root))
    }

    @Test
    fun `knows whether a server has written anything at all`() {
        assertFalse(ServerLogTail.hasHistory(root))

        logs()

        assertFalse(ServerLogTail.hasHistory(root))

        writeLatest("[10:00:00] [Server thread/INFO]: hello")

        assertTrue(ServerLogTail.hasHistory(root))
    }

    @Test
    fun `merge drops the overlap between file and ring`() {
        val file = (1..10).map { ConsoleLine(it.toLong(), "INFO", "line $it") }
        val ring = (8..12).map { ConsoleLine(it.toLong() * 1000, "INFO", "line $it") }

        val merged = ConsoleHistoryMerge.merge(file, ring, 500)

        assertEquals((1..12).map { "line $it" }, merged.map { it.m })

        // The ring's copy wins, so its timestamps are the ones that survive.
        assertEquals(8_000L, merged[7].t)
    }

    @Test
    fun `merge keeps everything when nothing overlaps`() {
        val file = (1..3).map { ConsoleLine(it.toLong(), "INFO", "old $it") }
        val ring = (1..3).map { ConsoleLine(it.toLong(), "INFO", "new $it") }

        assertEquals(
            listOf("old 1", "old 2", "old 3", "new 1", "new 2", "new 3"),
            ConsoleHistoryMerge.merge(file, ring, 500).map { it.m }
        )
    }

    @Test
    fun `merge prefers the longest overlap when a line repeats`() {
        val file = listOf("a", "same", "same", "same").map { ConsoleLine(1, "INFO", it) }
        val ring = listOf("same", "same", "same", "b").map { ConsoleLine(2, "INFO", it) }

        assertEquals(
            listOf("a", "same", "same", "same", "b"),
            ConsoleHistoryMerge.merge(file, ring, 500).map { it.m }
        )
    }

    @Test
    fun `merge trims to the limit keeping the newest lines`() {
        val file = (1..10).map { ConsoleLine(it.toLong(), "INFO", "line $it") }
        val ring = (9..12).map { ConsoleLine(it.toLong(), "INFO", "line $it") }

        assertEquals(
            listOf("line 9", "line 10", "line 11", "line 12"),
            ConsoleHistoryMerge.merge(file, ring, 4).map { it.m }
        )
    }

    @Test
    fun `merge handles an empty side`() {
        val ring = (1..3).map { ConsoleLine(it.toLong(), "INFO", "line $it") }

        assertEquals(ring.map { it.m }, ConsoleHistoryMerge.merge(emptyList(), ring, 500).map { it.m })
        assertEquals(ring.map { it.m }, ConsoleHistoryMerge.merge(ring, emptyList(), 500).map { it.m })
        assertEquals(emptyList<ConsoleLine>(), ConsoleHistoryMerge.merge(emptyList(), emptyList(), 500))
    }
}
