package com.panomc.node.console

import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.io.Reader
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.CodingErrorAction
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.zip.GZIPInputStream

/**
 * The scrollback a server wrote before anyone was listening.
 *
 * Both consoles in this system are per-process ring buffers: the node's [ConsoleBuffer] and Pano's
 * own. Restart either of them -- or simply start a server and open its console an hour later --
 * and the panel shows only what arrives from that moment on, although the server has been writing
 * every line of it to `logs/latest.log` the whole time. This reads those files back, newest line
 * last, so a console that opens cold opens on history.
 *
 * Every read is bounded. A log is an untrusted, unbounded file that a misbehaving plugin can grow
 * to gigabytes, so the tail is taken through a byte window rather than by loading the file, each
 * rotated file has its own decompression budget, only a few of them are ever opened, and a single
 * absurdly long line is truncated instead of being held whole.
 */
object ServerLogTail {
    /** How much of the end of `latest.log` may be read. Far more than 500 lines of anything. */
    const val TAIL_WINDOW_BYTES = 2L * 1024 * 1024

    /** How much of one rotated file may be decompressed before the rest of it is given up on. */
    const val MAX_FILE_BYTES = 4L * 1024 * 1024

    /** How much may be decompressed across all rotated files in one call. */
    const val MAX_TOTAL_BYTES = 8L * 1024 * 1024

    /** How many rotated files may be opened before the answer is simply "that is all there is". */
    const val MAX_ROTATED_FILES = 3

    /** Most lines one page may ask for. A console shows a screenful, not a log file. */
    const val MAX_LIMIT = 1000

    /**
     * How far back paging may step.
     *
     * Every page is read newest-first, so a page costs the lines it steps over as well as the
     * ones it returns. This is where "how much further back" stops being a question for a log
     * viewer and becomes one for the log file itself.
     */
    const val MAX_SKIP = 20_000

    /** Longest line kept. Matches the protocol's per-line cap, so nothing is cut twice. */
    const val MAX_LINE_LENGTH = ConsoleLineParser.MAX_MESSAGE_LENGTH

    /**
     * How many of the newest lines a search looks through (§2.4.20).
     *
     * Exactly what unfiltered paging can reach — the deepest page there is starts [MAX_SKIP]
     * lines back and is [MAX_LIMIT] long — so Find in the panel searches what "Load older" could
     * have shown and never a line further back. A search is not a reason to read more of the disk
     * than paging would.
     */
    const val SEARCH_WINDOW_LINES = MAX_SKIP + MAX_LIMIT

    /** Longest query honoured; the rest of a pasted paragraph is not a search term. */
    const val MAX_QUERY_LENGTH = 200

    /**
     * Longest record read back from the node's own `console.log` (§2.4.21 B).
     *
     * Longer than [MAX_LINE_LENGTH] because a record is the timestamp, a tab, the text *and* the
     * colour codes kept inside it — the text alone is still capped at [MAX_LINE_LENGTH] when it is
     * parsed, exactly as a live line is.
     */
    const val MAX_CONSOLE_RECORD_LENGTH = MAX_LINE_LENGTH + ConsoleLineParser.MAX_SGR_CHARS + 64

    /** Leading `[12:34:56]` or `[12:34:56 INFO]`, the only clock a Minecraft log line carries. */
    internal val LINE_TIME = Regex("^\\[(\\d{2}):(\\d{2}):(\\d{2})")

    /** `2025-08-16-14.log.gz` and friends: the day a rotated file belongs to is in its name. */
    internal val ROTATED_DATE = Regex("^(\\d{4})-(\\d{2})-(\\d{2})")

    /**
     * One page of the console history a server wrote, oldest line first.
     *
     * [skip] is how many of the newest lines to step over before the page starts: they are the
     * ones the panel already shows, and "give me the 500 before those" is the whole of paging.
     * Nothing is remembered between calls -- the page is found by reading backwards from the end
     * every time, which is why [skip] is bounded and why a page costs the lines it steps over.
     *
     * [Page.hasMore] means the reader stopped because the page was full, not because the files
     * ran out: it is the answer to "is there an older page", and it is only ever true when
     * something older was actually left behind.
     *
     * A non-blank [query] turns the page into a search (§2.4.20): see [search]. A blank or missing
     * one is the plain page above, exactly as it has always been.
     *
     * A missing or unreadable `logs` directory is not a failure: a server that has never started
     * has no history, and the console should open empty rather than error.
     */
    fun read(
        serverDirectory: File,
        limit: Int,
        skip: Int = 0,
        query: String? = null,
        zone: ZoneId = ZoneId.systemDefault()
    ): Page {
        val needle = searchNeedle(query)

        if (needle != null) {
            return search(serverDirectory, limit, skip, needle, zone)
        }

        if (limit <= 0) {
            return EMPTY
        }

        val take = limit.coerceAtMost(MAX_LIMIT)
        val step = skip.coerceIn(0, MAX_SKIP)
        val want = take + step

        val window = collect(serverDirectory, want, zone)
        val all = window.lines

        // An older page is only worth offering when this one came back full: a short page means
        // the reader reached the beginning of the history, wherever that beginning was.
        return Page(all.dropLast(step).takeLast(take), window.older && all.size >= want)
    }

    /**
     * One page of the lines in the search window that contain [needle], oldest line first
     * (§2.4.20).
     *
     * The window is [SEARCH_WINDOW_LINES] lines, read by exactly the same code and the same byte budgets
     * as an unfiltered page that deep, which is what makes "only within what Load older could
     * reach" true by construction rather than by a second reading of the rules. Then it is walked
     * newest to oldest and only matches count: [skip] steps over matches the panel already shows,
     * [limit] is how many matches come back. See [searchLines] for the rest.
     */
    fun search(serverDirectory: File, limit: Int, skip: Int, needle: String, zone: ZoneId = ZoneId.systemDefault()): Page {
        if (limit <= 0) {
            return EMPTY
        }

        return searchLines(collect(serverDirectory, SEARCH_WINDOW_LINES, zone).lines, limit, skip, needle)
    }

    /**
     * The search itself, over lines already in hand, oldest first (§2.4.20).
     *
     * Case-insensitive plain substring on the message text — not a regex, because a panel search
     * box is not a place to accept a pattern that can take the reader down with it. Walked newest
     * to oldest and stopped the moment the page is full, so what is kept is the page and nothing
     * else: no list of every match is ever built.
     *
     * [Page.hasMore] is true only when the page filled up with window still left unscanned below
     * it. It does not promise that what is left holds another match — finding out would mean
     * scanning it — only that asking again is not pointless.
     *
     * Also what the node uses on its own ring buffer when a server has no log files at all.
     */
    fun searchLines(lines: List<ConsoleLine>, limit: Int, skip: Int, needle: String): Page {
        if (limit <= 0) {
            return EMPTY
        }

        val take = limit.coerceAtMost(MAX_LIMIT)
        val step = skip.coerceIn(0, MAX_SKIP)

        val page = ArrayDeque<ConsoleLine>()

        var skipped = 0
        var index = lines.lastIndex

        while (index >= 0 && page.size < take) {
            val line = lines[index]

            index--

            if (!line.m.contains(needle, ignoreCase = true)) {
                continue
            }

            if (skipped < step) {
                skipped++

                continue
            }

            page.addFirst(line)
        }

        return Page(page.toList(), page.size >= take && index >= 0)
    }

    /**
     * What a search looks for, or null when [query] is not a search at all.
     *
     * Trimmed, because the leading space of a pasted line is not what anybody meant, and capped
     * at [MAX_QUERY_LENGTH]. Blank is null so that an empty Find box is the plain console, byte
     * for byte.
     */
    fun searchNeedle(query: String?): String? =
        query?.trim()?.take(MAX_QUERY_LENGTH)?.takeIf { it.isNotEmpty() }

    /** The newest [want] lines on disk, oldest first, and whether older ones were left behind. */
    private data class Collected(val lines: List<ConsoleLine>, val older: Boolean)

    /**
     * Reads the newest [want] lines of a server's history, from whichever source it has.
     *
     * The node's own `.pano-node/console.log` (+ `.1`, `.2`) when there is one (§2.4.21 B): it is
     * the console exactly as it went past, colours and the node's own lines included. Otherwise
     * `logs/latest.log` and the rotated files behind it, exactly as before — which is what a server
     * that has not run under this node since the upgrade, or an imported one, still has.
     *
     * Shared by the plain page and the search so the two can never disagree about how far back
     * the history reaches: a search window that read one more file than paging does would find
     * lines "Load older" could never show. Both sources go through [collectFrom], so the byte
     * windows, budgets and stop rules are the same whichever one answers.
     */
    private fun collect(serverDirectory: File, want: Int, zone: ZoneId): Collected {
        val console = ConsoleLogWriter.file(serverDirectory)

        if (console.isFile) {
            return collectFrom(console, ConsoleLogWriter.rotations(serverDirectory), want, MAX_CONSOLE_RECORD_LENGTH) { lines, _ ->
                parseConsoleLog(lines)
            }
        }

        val logs = File(serverDirectory, "logs")

        if (!logs.isDirectory) {
            return Collected(emptyList(), false)
        }

        val latest = File(logs, "latest.log").takeIf { it.isFile }

        return collectFrom(latest, rotated(logs), want, MAX_LINE_LENGTH) { lines, file ->
            parse(lines, dateOf(file, zone), zone, anchorEnd(file))
        }
    }

    /**
     * The newest [want] lines of one source: [latest] read through its tail window, then the
     * [rotated] files behind it, newest first, under the decompression budgets.
     */
    private fun collectFrom(
        latest: File?,
        rotated: List<File>,
        want: Int,
        lineCap: Int,
        parser: (List<String>, File) -> List<ConsoleLine>
    ): Collected {
        val collected = ArrayDeque<ConsoleLine>()

        // Whether anything older than the oldest collected line is still on disk. Only ever set
        // by a reader that had to leave something behind, so a false here means the files really
        // did run out rather than that nobody looked.
        var older = false

        // The byte window ran out before the page was full. What is older than it is still in the
        // file but out of reach, and carrying on into a rotated file would report a history with
        // a hole in the middle of it as though it were continuous.
        var walled = false

        if (latest != null) {
            val tail = try {
                tailLines(latest, want, lineCap)
            } catch (exception: Exception) {
                Tail(emptyList(), false)
            }

            val left = prepend(collected, parser(tail.lines, latest), want)

            older = left || tail.hasOlder
            walled = tail.hasOlder && collected.size < want
        }

        if (!walled) {
            var budget = MAX_TOTAL_BYTES
            var opened = 0

            for (file in rotated) {
                if (collected.size >= want || opened >= MAX_ROTATED_FILES || budget <= 0) {
                    // Stopped with files still unread: whatever is in them is older than this page.
                    older = true

                    break
                }

                opened++

                val raw = try {
                    gzipLines(file, want, minOf(MAX_FILE_BYTES, budget), lineCap)
                } catch (exception: Exception) {
                    continue
                }

                budget -= raw.bytesRead

                if (raw.hasOlder) {
                    older = true
                }

                if (prepend(collected, parser(raw.lines, file), want)) {
                    older = true
                }
            }
        }

        return Collected(collected.toList(), older)
    }

    /**
     * Records of the node's `console.log` turned into console lines (§2.4.21 B).
     *
     * `<epochMillis>\t<text with its SGR codes>`: the time is the one the node stamped the line
     * with when it went past, so nothing has to be guessed from a clock in the text, and the text
     * is parsed exactly as a live line is — the same plain `m`, the same spans. A record with no
     * readable time (a torn write at the start of a window) keeps the one before it.
     */
    fun parseConsoleLog(raw: List<String>): List<ConsoleLine> {
        var previous = 0L

        return raw.map { record ->
            val tab = record.indexOf('\t')
            val stamp = if (tab > 0) record.substring(0, tab).toLongOrNull() else null
            val text = if (stamp != null) record.substring(tab + 1) else record

            val time = stamp ?: previous

            previous = time

            ConsoleLineParser.toLine(text, time)
        }
    }

    /**
     * Whether this server has a log file at all.
     *
     * Asked when the ring buffer alone already filled a page: there is no point reading the files
     * to answer "is there more", but there is a difference between a server with a `logs`
     * directory behind it and one that has never written a line.
     */
    fun hasHistory(serverDirectory: File): Boolean {
        if (ConsoleLogWriter.file(serverDirectory).let { it.isFile && it.length() > 0L }) {
            return true
        }

        val logs = File(serverDirectory, "logs")

        if (!logs.isDirectory) {
            return false
        }

        return (logs.listFiles() ?: emptyArray())
            .any { it.isFile && it.length() > 0L && (it.name.endsWith(".log") || it.name.endsWith(".log.gz")) }
    }

    /** One page of history, and whether anything older than it is still on disk. */
    data class Page(val lines: List<ConsoleLine>, val hasMore: Boolean)

    /** The lines read off the end of a file, and whether that file still holds older ones. */
    data class Tail(val lines: List<String>, val hasOlder: Boolean)

    private val EMPTY = Page(emptyList(), false)

    /**
     * Puts an older file's lines in front of what is already collected, keeping the newest N.
     *
     * Reports whether anything had to be left behind, which is this file saying it still holds
     * lines older than the ones that fit.
     */
    private fun prepend(target: ArrayDeque<ConsoleLine>, older: List<ConsoleLine>, limit: Int): Boolean {
        val room = limit - target.size

        if (room <= 0) {
            return older.isNotEmpty()
        }

        older.takeLast(room).asReversed().forEach { target.addFirst(it) }

        return older.size > room
    }

    /**
     * The rotated logs, newest first.
     *
     * Dated names sort themselves, and anything without a date falls back to its mtime, which is
     * what a fork that rotates to `server.log.1` leaves behind.
     */
    private fun rotated(logs: File): List<File> = (logs.listFiles() ?: emptyArray())
        .filter { it.isFile && it.name != "latest.log" && (it.name.endsWith(".log.gz") || it.name.endsWith(".log")) }
        .sortedWith(compareByDescending<File> { it.name.takeIf { name -> ROTATED_DATE.containsMatchIn(name) } ?: "" }
            .thenByDescending { it.lastModified() }
            .thenByDescending { it.name })

    /**
     * The last lines of a plain file, read through a byte window.
     *
     * The window never starts at a line boundary, so the first fragment it catches is thrown away
     * rather than reported as a line that was never written.
     */
    fun tailLines(file: File, limit: Int, lineCap: Int = MAX_LINE_LENGTH): Tail {
        FileChannel.open(file.toPath(), StandardOpenOption.READ).use { channel ->
            val size = channel.size()

            if (size <= 0L) {
                return Tail(emptyList(), false)
            }

            val from = maxOf(0L, size - TAIL_WINDOW_BYTES)
            val bytes = ByteArray((size - from).toInt())

            channel.position(from)

            var read = 0

            while (read < bytes.size) {
                val count = channel.read(ByteBuffer.wrap(bytes, read, bytes.size - read))

                if (count <= 0) {
                    break
                }

                read += count
            }

            val text = decode(bytes, 0, read)
            val lines = split(text)

            // The fragment the window opened inside of is not a line anyone wrote, and a log file
            // ends with a newline, so its trailing empty piece is not one either. Blank lines go
            // the same way: they carry nothing and would spend the limit.
            val kept = (if (from > 0L && lines.isNotEmpty()) lines.drop(1) else lines)
                .filter { it.isNotBlank() }

            // Either the window did not reach the start of the file, or it did and still held
            // more lines than were asked for: both mean this file has older lines to give.
            return Tail(kept.takeLast(limit).map { cap(it, lineCap) }, from > 0L || kept.size > limit)
        }
    }

    /**
     * One rotated file's last lines, with a hard ceiling on how much of it is decompressed.
     *
     * A gzip stream can only be read forwards, so a file bigger than its budget gives up its
     * beginning rather than its end. That is the honest trade: it is still real history, and the
     * alternative is decompressing a gigabyte to reach the last screenful of it.
     */
    private fun gzipLines(file: File, limit: Int, byteBudget: Long, lineCap: Int = MAX_LINE_LENGTH): RawLines {
        val raw: InputStream = if (file.name.endsWith(".gz")) {
            GZIPInputStream(file.inputStream().buffered())
        } else {
            file.inputStream().buffered()
        }

        val budgeted = BudgetedStream(raw, byteBudget)

        budgeted.use { input ->
            val reader = InputStreamReader(
                input,
                Charsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPLACE)
                    .onUnmappableCharacter(CodingErrorAction.REPLACE)
            ).buffered()

            val kept = ArrayDeque<String>()

            var evicted = false

            while (true) {
                val line = readLine(reader, lineCap) ?: break

                if (line.isNotBlank() && push(kept, line, limit, lineCap)) {
                    evicted = true
                }
            }

            // The budget ran out somewhere inside a line; whatever was kept of it is a fragment,
            // and a fragment is not something to show an operator as a line the server printed.
            if (budgeted.isExhausted() && kept.isNotEmpty()) {
                kept.removeLast()
            }

            return RawLines(kept.toList(), budgeted.bytesRead(), evicted)
        }
    }

    /**
     * One line, capped as it is read.
     *
     * Reading it with [java.io.BufferedReader.readLine] would mean holding a whole line in memory
     * before deciding it is too long, and "too long" here can be the entire budget.
     */
    internal fun readLine(reader: Reader, lineCap: Int): String? {
        val builder = StringBuilder()

        var any = false

        while (true) {
            val value = reader.read()

            if (value < 0) {
                return if (any) builder.toString().trimEnd('\r') else null
            }

            any = true

            val char = value.toChar()

            if (char == '\n') {
                return builder.toString().trimEnd('\r')
            }

            if (builder.length < lineCap) {
                builder.append(char)
            }
        }
    }

    /** An input stream that stops at a byte budget and remembers that it did. */
    internal class BudgetedStream(private val delegate: InputStream, private val budget: Long) : InputStream() {
        private var read = 0L
        private var exhausted = false

        fun bytesRead(): Long = read

        fun isExhausted(): Boolean = exhausted

        override fun read(): Int {
            if (read >= budget) {
                exhausted = true

                return -1
            }

            val value = delegate.read()

            if (value >= 0) {
                read++
            }

            return value
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (read >= budget) {
                exhausted = true

                return -1
            }

            val count = delegate.read(buffer, offset, minOf(length.toLong(), budget - read).toInt())

            if (count > 0) {
                read += count
            }

            return count
        }

        override fun close() = delegate.close()
    }

    /** Keeps the newest [limit] lines and reports whether an older one had to go. */
    private fun push(kept: ArrayDeque<String>, line: String, limit: Int, lineCap: Int): Boolean {
        kept.addLast(cap(line, lineCap))

        var evicted = false

        while (kept.size > limit) {
            kept.removeFirst()

            evicted = true
        }

        return evicted
    }

    private data class RawLines(val lines: List<String>, val bytesRead: Long, val hasOlder: Boolean)

    /** UTF-8 with malformed bytes replaced: a window cut mid-character must not throw. */
    private fun decode(bytes: ByteArray, offset: Int, length: Int): String {
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE)

        return decoder.decode(ByteBuffer.wrap(bytes, offset, length)).toString()
    }

    private fun split(text: String): List<String> = text.split('\n').map { it.trimEnd('\r') }

    private fun cap(line: String, lineCap: Int = MAX_LINE_LENGTH): String =
        if (line.length > lineCap) line.substring(0, lineCap) else line

    /**
     * Turns one file's raw lines into console lines.
     *
     * A log line carries a time of day and nothing else, so the day comes from the file: its name
     * when that is dated, its mtime otherwise. Times that go backwards inside a file mean midnight
     * passed, so the day rolls forward. A line with no readable time inherits the timestamp before
     * it, and the very first such line is dated to that day at 00:00.
     *
     * [anchorEnd] is for a file dated by its mtime, where the known day is the day of the *last*
     * line rather than the first: `latest.log` read at one in the morning starts the evening
     * before, and dating its first line today would put the whole boot in the future.
     */
    fun parse(raw: List<String>, date: LocalDate, zone: ZoneId, anchorEnd: Boolean = false): List<ConsoleLine> {
        val first = parseFrom(raw, date, zone)

        if (!anchorEnd || first.rollovers == 0L) {
            return first.lines
        }

        return parseFrom(raw, date.minusDays(first.rollovers), zone).lines
    }

    private fun parseFrom(raw: List<String>, date: LocalDate, zone: ZoneId): Parsed {
        var day = date
        var previousTime: LocalTime? = null
        var previousStamp = LocalDateTime.of(date, LocalTime.MIDNIGHT).atZone(zone).toInstant().toEpochMilli()

        val lines = ArrayList<ConsoleLine>(raw.size)

        raw.forEach { line ->
            if (line.isEmpty()) {
                return@forEach
            }

            val stripped = ConsoleLineParser.stripAnsi(line)
            val match = LINE_TIME.find(stripped)

            val stamp = if (match == null) {
                previousStamp
            } else {
                val time = LocalTime.of(
                    match.groupValues[1].toInt().coerceAtMost(23),
                    match.groupValues[2].toInt().coerceAtMost(59),
                    match.groupValues[3].toInt().coerceAtMost(59)
                )

                val previous = previousTime

                if (previous != null && time < previous) {
                    day = day.plusDays(1)
                }

                previousTime = time

                LocalDateTime.of(day, time).atZone(zone).toInstant().toEpochMilli()
            }

            previousStamp = stamp

            lines.add(ConsoleLineParser.toLine(line, stamp))
        }

        return Parsed(lines, ChronoUnit.DAYS.between(date, day))
    }

    private data class Parsed(val lines: List<ConsoleLine>, val rollovers: Long)

    /** Whether a file's day has to be read off its last line rather than its first. */
    internal fun anchorEnd(file: File): Boolean = !ROTATED_DATE.containsMatchIn(file.name)

    /** The day a log file belongs to: the date in its name, or the day it was last written. */
    fun dateOf(file: File, zone: ZoneId): LocalDate {
        val match = ROTATED_DATE.find(file.name)

        if (match != null) {
            try {
                return LocalDate.of(
                    match.groupValues[1].toInt(),
                    match.groupValues[2].toInt(),
                    match.groupValues[3].toInt()
                )
            } catch (exception: Exception) {
                // A name that looks dated but is not; the mtime is still true.
            }
        }

        return Instant.ofEpochMilli(file.lastModified()).atZone(zone).toLocalDate()
    }
}
