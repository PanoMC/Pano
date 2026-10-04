package com.panomc.node.console

import io.vertx.core.json.JsonObject
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.CodingErrorAction
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.util.Base64
import java.util.zip.GZIPInputStream

/**
 * The console's deep search (`CONSOLE_SEARCH`): every log file a server has, not just the window
 * "Load older" can reach.
 *
 * The windowed search in [ServerLogTail.search] answers "is it on screen if I scroll back far
 * enough", which is the wrong question for "when did this player first join" or "has that
 * exception ever happened before". This one walks the whole `logs` directory, newest file first,
 * and hands back what it found a page at a time so the panel can show matches while the rest is
 * still being read and the operator can stop the moment the answer is on screen.
 *
 * Nothing is remembered between calls. The panel carries an opaque cursor that names the file to
 * resume at and how many of that file's matches it has already been given, so a call never repeats
 * work that an earlier one finished: a file is only ever read again when the previous page ended
 * inside it, which is the price of not keeping state on the node and a small one.
 *
 * The stream is strictly newest to oldest. Files are ordered by the date and index in their names,
 * and inside one file the matches are collected reading forwards (a gzip stream cannot be read any
 * other way) and handed out in reverse. Every line goes through the same parser, the same colour
 * spans and the same timestamp rules as a history page, so a match looks exactly like the line the
 * console would have shown.
 *
 * Every read is still bounded: a per-file decompression cap, a per-call time budget checked
 * between files and every few thousand lines inside one, and a page limit. A file bigger than the
 * cap gives up part of itself — the beginning of a gzip, the beginning of a plain file too, which
 * is read from its tail — and says so with `capped`, because a search that quietly skipped part of
 * the history would be worse than one that admits it.
 */
object ServerLogSearch {
    /** Matches one call returns when the caller did not say. */
    const val DEFAULT_LIMIT = 200

    /** Most matches one call may return, the same ceiling as a history page. */
    const val MAX_LIMIT = ServerLogTail.MAX_LIMIT

    /** How long one call may read when the caller did not say. */
    const val DEFAULT_BUDGET_MS = 1500

    /** Shortest budget honoured: less than this and a call would spend itself listing the files. */
    const val MIN_BUDGET_MS = 200

    /** Longest budget honoured: the reply still has to come back inside Pano's request timeout. */
    const val MAX_BUDGET_MS = 5000

    /**
     * How much of one file may be read (decompressed, for a gzip) before the rest of it is given
     * up on. Far more than a normal day of logs; a file past it is a plugin spamming, and the
     * newest end of a plain file or the oldest end of a gzip is what is left of it.
     */
    const val MAX_FILE_BYTES = 64L * 1024 * 1024

    /** How many lines are read between two looks at the clock inside one file. */
    const val BUDGET_CHECK_LINES = 10_000

    /**
     * Most matches of one file held in memory at once.
     *
     * A resumed call has to step over the matches it already emitted, which one pass can only do by
     * keeping them. Past this many the file is read twice instead — once to count, once to pick the
     * page out by position — so a query that matches every line of a huge file costs a second read
     * rather than a heap full of lines nobody will be sent.
     */
    const val ONE_PASS_WINDOW = 2000

    /**
     * Roughly how many bytes of lines one reply may carry.
     *
     * Pano's socket refuses a message past 256 KiB, and a thousand lines of four kilobytes each
     * would be sixteen times that. A page that reaches this stops early with its cursor inside the
     * file it was in, exactly as a full page does, so the panel just asks again; the Pano plugin
     * keeps its replies under the same figure.
     */
    const val MAX_REPLY_BYTES = 160 * 1024

    /** Longest cursor accepted; a real one is a few dozen characters. */
    const val MAX_CURSOR_LENGTH = 1024

    /** Cursor format version, so a node that changes what it means can refuse an old one cleanly. */
    const val CURSOR_VERSION = 1

    /** `f` of a match that came from the node's own console rather than a server log file. */
    const val CONSOLE_LABEL = "console"

    const val ERROR_BAD_QUERY = "BAD_QUERY"
    const val ERROR_BAD_CURSOR = "BAD_CURSOR"

    /** The disk failed under the search; the node answers with it rather than with an empty page. */
    const val ERROR_READ_FAILED = "READ_FAILED"

    /** `2026-09-22-3.log.gz`: the day, then the index the server gave that day's Nth file. */
    private val ROTATED_NAME = Regex("^(\\d{4}-\\d{2}-\\d{2})-(\\d+)")

    /** One match and the file it came from, as the `f` the panel groups by. */
    data class Match(val line: ConsoleLine, val file: String)

    /**
     * One call's answer.
     *
     * [error] is set, and everything else empty, for a request that could not be answered at all:
     * [ERROR_BAD_QUERY] or [ERROR_BAD_CURSOR]. Such an answer is `done` with no cursor, the same as
     * the plugin's, so a panel that only ever follows cursors can never loop on it. [scannedFiles] is how many files are behind the
     * cursor — finished, by this call or an earlier one — so it is progress the panel can show as
     * it stands; [scannedBytes] is what this call alone read.
     */
    data class Result(
        val matches: List<Match>,
        val cursor: String?,
        val done: Boolean,
        val scannedFiles: Int,
        val totalFiles: Int,
        val scannedBytes: Long,
        val capped: Boolean,
        val error: String? = null
    ) {
        companion object {
            fun error(code: String) = Result(emptyList(), null, true, 0, 0, 0L, false, code)
        }
    }

    /** Where a call resumes: the file, and how many of its newest matches were already emitted. */
    data class Cursor(val file: String, val emitted: Int)

    /**
     * One file a search can read.
     *
     * [name] is what the cursor names it by, [label] is the `f` its matches carry, and [records]
     * is true for the node's own `console.log`, whose lines carry their own epoch time rather than
     * a clock the day has to be worked out for.
     */
    private data class Source(val name: String, val file: File, val label: String, val records: Boolean)

    /**
     * Searches [serverDirectory]'s logs for [query], resuming at [cursor].
     *
     * [ring] is the node's in-memory console for the server, asked only when there is no log file
     * of any kind to read — a server that has never written one still has a console worth
     * searching. [clock] is milliseconds, injectable so a test can run the budget out on purpose.
     */
    fun search(
        serverDirectory: File,
        query: String?,
        cursor: String?,
        limit: Int?,
        budgetMs: Int?,
        ring: () -> List<ConsoleLine> = { emptyList() },
        zone: ZoneId = ZoneId.systemDefault(),
        clock: () -> Long = { System.nanoTime() / 1_000_000 }
    ): Result {
        val needle = ServerLogTail.searchNeedle(query) ?: return Result.error(ERROR_BAD_QUERY)

        val resume = if (cursor.isNullOrEmpty()) null else decodeCursor(cursor) ?: return Result.error(ERROR_BAD_CURSOR)

        val take = (limit ?: DEFAULT_LIMIT).coerceIn(1, MAX_LIMIT)
        val budget = (budgetMs ?: DEFAULT_BUDGET_MS).coerceIn(MIN_BUDGET_MS, MAX_BUDGET_MS)

        val sources = sources(serverDirectory)

        if (sources.isEmpty()) {
            return searchRing(ring, needle, resume, take)
        }

        val start = if (resume == null) 0 else sources.indexOfFirst { it.name == resume.file }

        // A file that is no longer there — rotated away, deleted — is a cursor this node cannot
        // honour, and guessing where to carry on would either repeat or skip matches.
        if (start < 0) {
            return Result.error(ERROR_BAD_CURSOR)
        }

        val deadline = clock() + budget
        val matches = ArrayList<Match>()

        var index = start
        var skip = resume?.emitted ?: 0
        var bytes = 0L
        var capped = false
        var next: Cursor? = null
        var readAny = false
        var replyBytes = 0L

        while (index < sources.size) {
            val source = sources[index]

            if (matches.size >= take || (readAny && clock() >= deadline)) {
                next = Cursor(source.name, skip)

                break
            }

            val room = take - matches.size

            // The first file of a call always finishes, however long it takes: otherwise one file
            // bigger than the budget would be restarted forever and the search would never move.
            val scan = try {
                scanFile(source, needle, skip, room, zone, if (readAny) deadline else null, clock)
            } catch (exception: Exception) {
                // An unreadable file is a file with nothing to find, not a search that failed.
                FileScan(emptyList(), 0, 0L, capped = false, aborted = false)
            }

            readAny = true
            bytes += scan.bytes

            if (scan.aborted) {
                next = Cursor(source.name, skip)

                break
            }

            if (scan.capped) {
                capped = true
            }

            // Always at least one match per call, whatever its size, so a page can never be empty
            // for want of room and the search always moves.
            var added = 0

            for (line in scan.page) {
                val size = replySize(line, source.label)

                if (matches.isNotEmpty() && replyBytes + size > MAX_REPLY_BYTES) {
                    break
                }

                matches.add(Match(line, source.label))
                replyBytes += size
                added++
            }

            val left = scan.total - skip - added

            if (left > 0) {
                next = Cursor(source.name, skip + added)

                break
            }

            if (replyBytes >= MAX_REPLY_BYTES) {
                // The file is finished but the reply is full: carry on at the next one next time.
                index++
                skip = 0

                if (index < sources.size) {
                    next = Cursor(sources[index].name, 0)
                }

                break
            }

            index++
            skip = 0
        }

        return Result(
            matches = matches,
            cursor = next?.let { encodeCursor(it) },
            done = next == null,
            scannedFiles = if (next == null) sources.size else index,
            totalFiles = sources.size,
            scannedBytes = bytes,
            capped = capped
        )
    }

    /**
     * About how many bytes [line] adds to a reply as JSON: its text in UTF-8, the escapes a control
     * character costs, its colour spans, the field names and its file label. An estimate, erring
     * high, which is all [MAX_REPLY_BYTES] needs.
     */
    private fun replySize(line: ConsoleLine, file: String): Long {
        var bytes = 96L + file.length + (line.c?.size ?: 0) * 40L

        for (char in line.m) {
            bytes += when {
                char.code < 0x20 || char == '"' || char == '\\' -> 6
                char.code < 0x80 -> 1
                char.code < 0x800 -> 2
                else -> 3
            }
        }

        return bytes
    }

    /**
     * The node's in-memory console, searched once, when there is no file to search.
     *
     * Paged by the same cursor as a file, named [CONSOLE_LABEL], so a ring holding more matches
     * than one page still pages instead of silently stopping. The ring moves between calls, so a
     * page further down may overlap or miss a line that scrolled — it is a fallback, not a record.
     */
    private fun searchRing(ring: () -> List<ConsoleLine>, needle: String, resume: Cursor?, take: Int): Result {
        if (resume != null && resume.file != CONSOLE_LABEL) {
            return Result.error(ERROR_BAD_CURSOR)
        }

        val skip = resume?.emitted ?: 0
        val newestFirst = ring().asReversed().filter { it.m.contains(needle, ignoreCase = true) }
        val page = ArrayList<ConsoleLine>()

        var replyBytes = 0L

        for (line in newestFirst.drop(skip)) {
            val size = replySize(line, CONSOLE_LABEL)

            if (page.size >= take || (page.isNotEmpty() && replyBytes + size > MAX_REPLY_BYTES)) {
                break
            }

            page.add(line)
            replyBytes += size
        }

        val left = newestFirst.size - skip - page.size

        val next = if (left > 0) Cursor(CONSOLE_LABEL, skip + page.size) else null

        return Result(
            matches = page.map { Match(it, CONSOLE_LABEL) },
            cursor = next?.let { encodeCursor(it) },
            done = next == null,
            scannedFiles = if (next == null) 1 else 0,
            totalFiles = 1,
            scannedBytes = 0L,
            capped = false
        )
    }

    /**
     * Everything a search reads, newest first.
     *
     * `logs/latest.log`, then every other `.log` and `.log.gz` beside it by the date and index in
     * its name, newest first, with an undated file after the dated ones by its mtime. No limit on
     * how many: that is the whole point of this search. Only when the `logs` directory holds none
     * of them is the node's own `console.log` (and its rotations) read instead, which is what a
     * server that writes no log files of its own still has.
     */
    private fun sources(serverDirectory: File): List<Source> {
        val logs = File(serverDirectory, "logs")

        val files = if (logs.isDirectory) {
            (logs.listFiles() ?: emptyArray()).filter { it.isFile && (it.name.endsWith(".log") || it.name.endsWith(".log.gz")) }
        } else {
            emptyList()
        }

        if (files.isNotEmpty()) {
            val latest = files.filter { it.name == "latest.log" }
            val rotated = sortNewestFirst(files.filter { it.name != "latest.log" })

            return (latest + rotated).map { Source(it.name, it, it.name, records = false) }
        }

        val console = ConsoleLogWriter.file(serverDirectory)

        if (!console.isFile) {
            return emptyList()
        }

        return (listOf(console) + ConsoleLogWriter.rotations(serverDirectory))
            .map { Source(it.name, it, CONSOLE_LABEL, records = true) }
    }

    /**
     * Rotated files, newest first by the name the server gave them.
     *
     * Sorting the names as text is what [ServerLogTail] gets away with for a handful of files, and
     * it is wrong the moment a day has ten of them: `-10` sorts before `-3`. Here the index is a
     * number. A name with no date falls back to its mtime, after every dated one.
     */
    fun sortNewestFirst(files: List<File>): List<File> = files.sortedWith(
        compareByDescending<File> { ROTATED_NAME.find(it.name)?.groupValues?.get(1) ?: "" }
            .thenByDescending { ROTATED_NAME.find(it.name)?.groupValues?.get(2)?.toLongOrNull() ?: -1L }
            .thenByDescending { it.lastModified() }
            .thenByDescending { it.name }
    )

    /**
     * What one file gave: this call's [page] of its matches, newest first, and how many it holds
     * in [total], which is what tells the caller whether the file has more to give.
     */
    private data class FileScan(
        val page: List<ConsoleLine>,
        val total: Int,
        val bytes: Long,
        val capped: Boolean,
        val aborted: Boolean
    )

    /**
     * One file's matches: the [room] newest after the [skip] newest, newest first.
     *
     * One pass keeping the last `skip + room` matches when that is small, otherwise one pass to
     * count and a second to pick the page out by position — see [ONE_PASS_WINDOW]. [deadline] is
     * null for the first file of a call, which is never cut short.
     */
    private fun scanFile(
        source: Source,
        needle: String,
        skip: Int,
        room: Int,
        zone: ZoneId,
        deadline: Long?,
        clock: () -> Long
    ): FileScan {
        val want = skip.toLong() + room

        if (want <= ONE_PASS_WINDOW) {
            val pass = readFile(source, needle, zone, deadline, clock, Keep.Last(want.toInt()))

            if (pass.aborted) {
                return FileScan(emptyList(), 0, pass.bytes, pass.capped, aborted = true)
            }

            val page = pass.kept.asReversed().drop(skip).take(room).map { it.toLine(pass.base) }

            return FileScan(page, pass.total, pass.bytes, pass.capped, aborted = false)
        }

        val count = readFile(source, needle, zone, deadline, clock, Keep.None)

        if (count.aborted) {
            return FileScan(emptyList(), 0, count.bytes, count.capped, aborted = true)
        }

        // Newest-first position j is forward position total - 1 - j, so the page [skip, skip + room)
        // is the forward range below. Forward positions of lines already written never move while
        // a file is appended to, which is what makes the second read find the same lines.
        val to = count.total - skip
        val from = maxOf(0, to - room)

        if (to <= 0) {
            return FileScan(emptyList(), count.total, count.bytes, count.capped, aborted = false)
        }

        val pick = readFile(source, needle, zone, deadline, clock, Keep.Range(from, to))

        if (pick.aborted) {
            return FileScan(emptyList(), 0, count.bytes + pick.bytes, pick.capped, aborted = true)
        }

        val page = pick.kept.asReversed().map { it.toLine(pick.base) }

        return FileScan(page, count.total, count.bytes + pick.bytes, count.capped || pick.capped, aborted = false)
    }

    /** Which matches a read holds on to. */
    private sealed class Keep {
        object None : Keep()

        /** The last [size] matches of the file. */
        class Last(val size: Int) : Keep()

        /** Matches at forward positions `[from, to)`. */
        class Range(val from: Int, val to: Int) : Keep()
    }

    /**
     * A match waiting for its timestamp.
     *
     * A server log's day is only certain once the whole file has been read — `latest.log` is dated
     * by its mtime, which is the day of its last line — so a match keeps the day offset and time of
     * day it was seen at, and becomes an epoch millisecond at the end. A `console.log` record
     * carries its own epoch, in [epoch].
     */
    private class Pending(val raw: String, val dayOffset: Long, val time: LocalTime, val epoch: Long?, val zone: ZoneId) {
        fun toLine(base: LocalDate): ConsoleLine {
            val stamp = epoch ?: LocalDateTime.of(base.plusDays(dayOffset), time).atZone(zone).toInstant().toEpochMilli()

            return ConsoleLineParser.toLine(raw, stamp)
        }
    }

    private class Pass(
        val kept: List<Pending>,
        val total: Int,
        val base: LocalDate,
        val bytes: Long,
        val capped: Boolean,
        val aborted: Boolean
    )

    /**
     * Reads one file forwards once, counting its matches and keeping the ones [keep] asks for.
     *
     * The clock is followed line by line exactly as [ServerLogTail.parse] follows it, so a match
     * carries the timestamp the same line would have in a history page: a time that goes backwards
     * rolls the day, a line without one inherits the one before it, and a file dated by its mtime
     * is shifted back by however many midnights it crossed.
     */
    private fun readFile(
        source: Source,
        needle: String,
        zone: ZoneId,
        deadline: Long?,
        clock: () -> Long,
        keep: Keep
    ): Pass {
        val date = if (source.records) LocalDate.now(zone) else ServerLogTail.dateOf(source.file, zone)
        val lineCap = if (source.records) ServerLogTail.MAX_CONSOLE_RECORD_LENGTH else ServerLogTail.MAX_LINE_LENGTH

        val opened = open(source.file)

        val kept = ArrayDeque<Pending>()

        var total = 0
        var lines = 0L
        var aborted = false

        // The day as ServerLogTail.parseFrom walks it: offset from the file's own date, the last
        // time of day seen, and the stamp a line without one inherits.
        var dayOffset = 0L
        var previousTime: LocalTime? = null
        var stampOffset = 0L
        var stampTime = LocalTime.MIDNIGHT
        var previousEpoch = 0L

        opened.stream.use { input ->
            val reader = InputStreamReader(
                input,
                Charsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPLACE)
                    .onUnmappableCharacter(CodingErrorAction.REPLACE)
            ).buffered()

            // A plain file read from inside its tail opened in the middle of a line.
            if (opened.dropFirst) {
                ServerLogTail.readLine(reader, lineCap)
            }

            while (true) {
                val line = ServerLogTail.readLine(reader, lineCap) ?: break

                lines++

                if (deadline != null && lines % BUDGET_CHECK_LINES == 0L && clock() >= deadline) {
                    aborted = true

                    break
                }

                if (line.isBlank()) {
                    continue
                }

                var text = line
                var epoch: Long? = null

                if (source.records) {
                    val tab = line.indexOf('\t')
                    val stamp = if (tab > 0) line.substring(0, tab).toLongOrNull() else null

                    if (stamp != null) {
                        text = line.substring(tab + 1)
                    }

                    epoch = stamp ?: previousEpoch
                    previousEpoch = epoch
                } else {
                    val stripped = if (line.indexOf('\u001B') >= 0) ConsoleLineParser.stripAnsi(line) else line
                    val match = ServerLogTail.LINE_TIME.find(stripped)

                    if (match != null) {
                        val time = LocalTime.of(
                            match.groupValues[1].toInt().coerceAtMost(23),
                            match.groupValues[2].toInt().coerceAtMost(59),
                            match.groupValues[3].toInt().coerceAtMost(59)
                        )

                        val previous = previousTime

                        if (previous != null && time < previous) {
                            dayOffset++
                        }

                        previousTime = time
                        stampOffset = dayOffset
                        stampTime = time
                    }
                }

                if (!textOf(text).contains(needle, ignoreCase = true)) {
                    continue
                }

                val position = total

                total++

                when (keep) {
                    Keep.None -> Unit
                    is Keep.Last -> {
                        kept.addLast(Pending(text, stampOffset, stampTime, epoch, zone))

                        if (kept.size > keep.size) {
                            kept.removeFirst()
                        }
                    }
                    is Keep.Range -> if (position >= keep.from && position < keep.to) {
                        kept.addLast(Pending(text, stampOffset, stampTime, epoch, zone))
                    }
                }
            }
        }

        val base = if (!source.records && ServerLogTail.anchorEnd(source.file) && dayOffset > 0) {
            date.minusDays(dayOffset)
        } else {
            date
        }

        return Pass(
            kept = kept.toList(),
            total = total,
            base = base,
            bytes = opened.budgeted.bytesRead(),
            capped = opened.capped || opened.budgeted.isExhausted(),
            aborted = aborted
        )
    }

    /**
     * The plain text a line would show as `m`, without building its colour spans.
     *
     * Exactly [ConsoleLineParser.styled]'s text: the line itself when it holds no escape at all,
     * which is nearly every line of a log file, capped as `m` is capped.
     */
    private fun textOf(line: String): String = if (line.indexOf('\u001B') < 0) {
        if (line.length > ConsoleLineParser.MAX_MESSAGE_LENGTH) line.substring(0, ConsoleLineParser.MAX_MESSAGE_LENGTH) else line
    } else {
        ConsoleLineParser.styled(line).text
    }

    private class Opened(
        val stream: InputStream,
        val budgeted: ServerLogTail.BudgetedStream,
        val capped: Boolean,
        val dropFirst: Boolean
    )

    /**
     * One file opened for a forward read under [MAX_FILE_BYTES].
     *
     * A gzip can only be read from its start, so a huge one gives up its end. A plain file can be
     * entered anywhere, so a huge `latest.log` is read from [MAX_FILE_BYTES] before its end — the
     * newest part is what a search most wants — and the line that window opens inside of is
     * thrown away rather than reported as something the server wrote.
     */
    private fun open(file: File): Opened {
        if (file.name.endsWith(".gz")) {
            val budgeted = ServerLogTail.BudgetedStream(GZIPInputStream(file.inputStream().buffered()), MAX_FILE_BYTES)

            return Opened(budgeted, budgeted, capped = false, dropFirst = false)
        }

        val input = FileInputStream(file)

        try {
            val from = maxOf(0L, input.channel.size() - MAX_FILE_BYTES)

            input.channel.position(from)

            val budgeted = ServerLogTail.BudgetedStream(input.buffered(), MAX_FILE_BYTES)

            return Opened(budgeted, budgeted, capped = from > 0L, dropFirst = from > 0L)
        } catch (exception: Exception) {
            input.close()

            throw exception
        }
    }

    /** `{v, file, emitted}` as unpadded base64url JSON: opaque to everybody but this object. */
    fun encodeCursor(cursor: Cursor): String {
        val json = JsonObject()
            .put("v", CURSOR_VERSION)
            .put("file", cursor.file)
            .put("emitted", cursor.emitted)

        return Base64.getUrlEncoder().withoutPadding().encodeToString(json.encode().toByteArray(Charsets.UTF_8))
    }

    /**
     * A cursor back out of its wire form, or null when it is not one this node wrote.
     *
     * Strict on purpose: the file must be a bare name — a cursor is a client-held string and a
     * path in it must never reach the disk — and the count a non-negative integer.
     */
    fun decodeCursor(raw: String): Cursor? {
        if (raw.length > MAX_CURSOR_LENGTH) {
            return null
        }

        return try {
            val json = JsonObject(String(Base64.getUrlDecoder().decode(raw.trimEnd('=')), Charsets.UTF_8))

            val version = json.getValue("v") as? Number
            val file = json.getValue("file") as? String
            val emitted = json.getValue("emitted") as? Number

            when {
                version == null || version.toInt() != CURSOR_VERSION -> null
                file.isNullOrBlank() || file.length > 255 || file.contains('/') || file.contains('\\') -> null
                file == "." || file == ".." -> null
                emitted == null || emitted.toDouble() != emitted.toLong().toDouble() -> null
                emitted.toLong() < 0 || emitted.toLong() > Int.MAX_VALUE -> null
                else -> Cursor(file, emitted.toInt())
            }
        } catch (exception: Exception) {
            null
        }
    }
}
