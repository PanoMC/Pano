package com.panomc.node

import com.panomc.node.console.ConsoleLine
import com.panomc.node.console.ServerLogSearch
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Base64
import java.util.zip.GZIPOutputStream

class ServerLogSearchTest {
    @TempDir
    lateinit var root: File

    private val zone: ZoneId = ZoneId.of("UTC")

    private fun logs(): File = File(root, "logs").apply { mkdirs() }

    private fun writeLatest(vararg lines: String): File =
        File(logs(), "latest.log").apply { writeText(lines.joinToString("\n") + "\n") }

    private fun writeGz(name: String, vararg lines: String): File {
        val file = File(logs(), name)

        GZIPOutputStream(file.outputStream()).use { it.write((lines.joinToString("\n") + "\n").toByteArray()) }

        return file
    }

    private fun line(time: String, text: String) = "[$time] [Server thread/INFO]: $text"

    private fun search(
        query: String? = "hit",
        cursor: String? = null,
        limit: Int? = null,
        budgetMs: Int? = null,
        ring: () -> List<ConsoleLine> = { emptyList() },
        clock: () -> Long = { 0L }
    ) = ServerLogSearch.search(root, query, cursor, limit, budgetMs, ring, zone, clock)

    /** Every page until done, as `m` suffixes, checking no call repeats or loses anything. */
    private fun drain(limit: Int, clock: () -> Long = { 0L }): List<ServerLogSearch.Result> {
        val pages = ArrayList<ServerLogSearch.Result>()

        var cursor: String? = null

        do {
            val page = search(cursor = cursor, limit = limit, clock = clock)

            assertNull(page.error)

            pages.add(page)

            cursor = page.cursor
        } while (!page.done && pages.size < 100)

        return pages
    }

    private fun texts(result: ServerLogSearch.Result) = result.matches.map { it.line.m.substringAfter("]: ") }

    @Test
    fun `files are searched latest first then rotated newest first by date and numeric index`() {
        writeLatest(line("12:00:00", "hit latest"))
        writeGz("2026-09-20-1.log.gz", line("10:00:00", "hit 20-1"))
        writeGz("2026-09-22-3.log.gz", line("10:00:00", "hit 22-3"))
        writeGz("2026-09-22-10.log.gz", line("10:00:00", "hit 22-10"))
        writeGz("2026-09-21-2.log.gz", line("10:00:00", "hit 21-2"))

        val result = search()

        assertEquals(listOf("hit latest", "hit 22-10", "hit 22-3", "hit 21-2", "hit 20-1"), texts(result))
        assertEquals(
            listOf("latest.log", "2026-09-22-10.log.gz", "2026-09-22-3.log.gz", "2026-09-21-2.log.gz", "2026-09-20-1.log.gz"),
            result.matches.map { it.file }
        )
        assertTrue(result.done)
        assertNull(result.cursor)
        assertEquals(5, result.scannedFiles)
        assertEquals(5, result.totalFiles)
        assertFalse(result.capped)
        assertTrue(result.scannedBytes > 0)
    }

    @Test
    fun `matches inside one file come newest line first with the rotated file's date`() {
        writeGz(
            "2026-09-22-1.log.gz",
            line("10:00:00", "hit one"),
            line("10:00:01", "miss"),
            line("10:00:02", "HIT two"),
            line("10:00:03", "hit three")
        )

        val result = search()

        assertEquals(listOf("hit three", "HIT two", "hit one"), texts(result))

        val expected = LocalDateTime.of(2026, 9, 22, 10, 0, 3).atZone(zone).toInstant().toEpochMilli()

        assertEquals(expected, result.matches.first().line.t)
        assertEquals("INFO", result.matches.first().line.l)
    }

    @Test
    fun `a day rollover inside a rotated file moves the later lines to the next day`() {
        writeGz(
            "2026-09-22-1.log.gz",
            line("23:59:59", "hit before"),
            line("00:00:01", "hit after")
        )

        val result = search()

        assertEquals(LocalDateTime.of(2026, 9, 23, 0, 0, 1).atZone(zone).toInstant().toEpochMilli(), result.matches[0].line.t)
        assertEquals(LocalDateTime.of(2026, 9, 22, 23, 59, 59).atZone(zone).toInstant().toEpochMilli(), result.matches[1].line.t)
    }

    @Test
    fun `a cursor resumes inside a file with emitted and never repeats a match`() {
        writeLatest(*(1..7).map { line("10:00:%02d".format(it), "hit $it") }.toTypedArray())
        writeGz("2026-09-22-1.log.gz", line("09:00:00", "hit old"))

        val pages = drain(limit = 3)

        assertEquals(
            listOf(listOf("hit 7", "hit 6", "hit 5"), listOf("hit 4", "hit 3", "hit 2"), listOf("hit 1", "hit old")),
            pages.map { texts(it) }
        )

        assertEquals(0, pages[0].scannedFiles)
        assertEquals(2, pages[0].totalFiles)
        assertEquals(ServerLogSearch.Cursor("latest.log", 3), ServerLogSearch.decodeCursor(pages[0].cursor!!))
        assertEquals(ServerLogSearch.Cursor("latest.log", 6), ServerLogSearch.decodeCursor(pages[1].cursor!!))
        assertTrue(pages[2].done)
        assertEquals(2, pages[2].scannedFiles)
    }

    @Test
    fun `resuming deep inside a file past the one pass window reads it twice and still pages right`() {
        val count = ServerLogSearch.ONE_PASS_WINDOW + 500

        writeLatest(*(1..count).map { line("10:00:00", "hit $it") }.toTypedArray())

        val cursor = ServerLogSearch.encodeCursor(ServerLogSearch.Cursor("latest.log", ServerLogSearch.ONE_PASS_WINDOW))
        val page = search(cursor = cursor, limit = 3)

        assertEquals(listOf("hit 500", "hit 499", "hit 498"), texts(page))
        assertEquals(ServerLogSearch.Cursor("latest.log", ServerLogSearch.ONE_PASS_WINDOW + 3), ServerLogSearch.decodeCursor(page.cursor!!))
    }

    @Test
    fun `the limit filled exactly at the end of a file cuts cleanly to the next file`() {
        writeLatest(line("10:00:00", "hit a"), line("10:00:01", "hit b"))
        writeGz("2026-09-22-1.log.gz", line("09:00:00", "hit c"))

        val first = search(limit = 2)

        assertEquals(listOf("hit b", "hit a"), texts(first))
        assertFalse(first.done)
        assertEquals(ServerLogSearch.Cursor("2026-09-22-1.log.gz", 0), ServerLogSearch.decodeCursor(first.cursor!!))
        assertEquals(1, first.scannedFiles)

        val second = search(cursor = first.cursor, limit = 2)

        assertEquals(listOf("hit c"), texts(second))
        assertTrue(second.done)
    }

    @Test
    fun `the budget cuts between files but the first file of a call always finishes`() {
        writeLatest(line("10:00:00", "hit a"))
        writeGz("2026-09-22-2.log.gz", line("09:00:00", "hit b"))
        writeGz("2026-09-22-1.log.gz", line("08:00:00", "hit c"))

        // Every look at the clock is a second later, so the budget is gone after the first file.
        var now = 0L
        val clock = { now.also { now += 1000 } }

        val first = search(budgetMs = 200, clock = clock)

        assertEquals(listOf("hit a"), texts(first))
        assertFalse(first.done)
        assertEquals(ServerLogSearch.Cursor("2026-09-22-2.log.gz", 0), ServerLogSearch.decodeCursor(first.cursor!!))

        val second = search(cursor = first.cursor, budgetMs = 200, clock = clock)

        assertEquals(listOf("hit b"), texts(second))

        val third = search(cursor = second.cursor, budgetMs = 200, clock = clock)

        assertEquals(listOf("hit c"), texts(third))
        assertTrue(third.done)
    }

    @Test
    fun `a file the budget runs out inside is restarted by the next call, not half emitted`() {
        writeLatest(line("10:00:00", "hit a"))
        writeGz(
            "2026-09-22-1.log.gz",
            *(1..ServerLogSearch.BUDGET_CHECK_LINES + 5).map { line("09:00:00", "hit $it") }.toTypedArray()
        )

        // Plenty of time for the first file, none left by the first look inside the second one.
        var calls = 0
        val clock = { if (calls++ < 2) 0L else 10_000L }

        val first = search(budgetMs = 1000, clock = clock)

        assertEquals(listOf("hit a"), texts(first))
        assertEquals(ServerLogSearch.Cursor("2026-09-22-1.log.gz", 0), ServerLogSearch.decodeCursor(first.cursor!!))

        val second = search(cursor = first.cursor, limit = 2, budgetMs = 1000, clock = { 10_000L })

        assertEquals(listOf("hit ${ServerLogSearch.BUDGET_CHECK_LINES + 5}", "hit ${ServerLogSearch.BUDGET_CHECK_LINES + 4}"), texts(second))
    }

    @Test
    fun `blank query is BAD_QUERY and a query is trimmed and matched case insensitively`() {
        writeLatest(line("10:00:00", "Player joined"))

        assertEquals(ServerLogSearch.ERROR_BAD_QUERY, search(query = "   ").error)
        assertEquals(ServerLogSearch.ERROR_BAD_QUERY, search(query = null).error)
        assertEquals(1, search(query = "  PLAYER ").matches.size)
    }

    @Test
    fun `garbage, foreign and stale cursors are BAD_CURSOR`() {
        writeLatest(line("10:00:00", "hit"))

        fun encode(json: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(json.toByteArray())

        assertEquals(ServerLogSearch.ERROR_BAD_CURSOR, search(cursor = "!!!").error)
        assertEquals(ServerLogSearch.ERROR_BAD_CURSOR, search(cursor = encode("not json")).error)
        assertEquals(ServerLogSearch.ERROR_BAD_CURSOR, search(cursor = encode("""{"v":2,"file":"latest.log","emitted":0}""")).error)
        assertEquals(ServerLogSearch.ERROR_BAD_CURSOR, search(cursor = encode("""{"v":1,"file":"../latest.log","emitted":0}""")).error)
        assertEquals(ServerLogSearch.ERROR_BAD_CURSOR, search(cursor = encode("""{"v":1,"file":"latest.log","emitted":-1}""")).error)
        assertEquals(ServerLogSearch.ERROR_BAD_CURSOR, search(cursor = encode("""{"v":1,"file":"gone.log.gz","emitted":0}""")).error)
        assertEquals(ServerLogSearch.ERROR_BAD_CURSOR, search(cursor = "a".repeat(ServerLogSearch.MAX_CURSOR_LENGTH + 1)).error)

        assertNull(search(cursor = encode("""{"v":1,"file":"latest.log","emitted":0}""")).error)
    }

    @Test
    fun `a server with no log files searches its ring once as console`() {
        val ring = (1..5).map { ConsoleLine(it.toLong(), "INFO", if (it % 2 == 1) "hit $it" else "miss $it") }

        val first = search(limit = 2, ring = { ring })

        assertEquals(listOf("hit 5", "hit 3"), first.matches.map { it.line.m })
        assertEquals(listOf("console", "console"), first.matches.map { it.file })
        assertFalse(first.done)
        assertEquals(1, first.totalFiles)

        val second = search(cursor = first.cursor, limit = 2, ring = { ring })

        assertEquals(listOf("hit 1"), second.matches.map { it.line.m })
        assertTrue(second.done)

        assertEquals(ServerLogSearch.ERROR_BAD_CURSOR, search(cursor = ServerLogSearch.encodeCursor(ServerLogSearch.Cursor("latest.log", 0)), ring = { ring }).error)
    }

    @Test
    fun `nothing anywhere is an empty finished search`() {
        val result = search()

        assertTrue(result.matches.isEmpty())
        assertTrue(result.done)
        assertNull(result.error)
    }

    @Test
    fun `a gzip bigger than the per file cap is cut short and says so`() {
        val file = File(logs(), "2026-09-22-1.log.gz")
        val filler = (line("10:00:00", "filler ".repeat(100)) + "\n").toByteArray()
        val target = ServerLogSearch.MAX_FILE_BYTES + filler.size * 10L

        GZIPOutputStream(file.outputStream().buffered()).use { out ->
            out.write((line("09:00:00", "hit first") + "\n").toByteArray())

            var written = 0L

            while (written < target) {
                out.write(filler)

                written += filler.size
            }

            out.write((line("11:00:00", "hit lost") + "\n").toByteArray())
        }

        val result = search()

        assertTrue(result.capped)
        assertEquals(listOf("hit first"), texts(result))
        assertTrue(result.done)
    }

    @Test
    fun `lines carry exactly the timestamps and text a history page gives them`() {
        writeLatest(
            line("23:59:58", "hit before midnight"),
            "\u001B[31mhit coloured\u001B[0m",
            line("00:00:02", "hit after midnight")
        )

        val history = com.panomc.node.console.ServerLogTail.read(root, 500, zone = zone).lines.asReversed()
        val found = search().matches.map { it.line }

        assertEquals(history, found)
    }

    @Test
    fun `a page stops at the reply byte cap and the cursor carries on inside the file`() {
        writeLatest(*(1..100).map { line("10:00:00", "hit $it " + "x".repeat(3000)) }.toTypedArray())

        val pages = drain(limit = 1000)

        assertTrue(pages.size > 1)
        assertTrue(pages.first().matches.size < 100)

        val all = pages.flatMap { texts(it) }.map { it.split(' ')[1] }

        assertEquals((100 downTo 1).map { it.toString() }, all)
    }

    @Test
    fun `cursor round trips`() {
        val cursor = ServerLogSearch.Cursor("2026-09-22-3.log.gz", 42)
        val encoded = ServerLogSearch.encodeCursor(cursor)

        assertFalse(encoded.contains('='))
        assertNotNull(ServerLogSearch.decodeCursor(encoded))
        assertEquals(cursor, ServerLogSearch.decodeCursor(encoded))
    }
}
