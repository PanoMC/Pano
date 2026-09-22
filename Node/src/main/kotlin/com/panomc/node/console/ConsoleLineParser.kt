package com.panomc.node.console

/**
 * Turns a raw stdout or stderr line into a level and clean text.
 *
 * A Minecraft server writes its level into the line itself and the node has no other way to know
 * it: there is no structured channel, only a pipe. Two shapes cover everything Mojang has shipped
 * plus every fork of it -- the modern `[12:34:56] [Server thread/INFO]:` and the older
 * `[12:34:56 INFO]:` -- and anything that matches neither is INFO, because a line whose level
 * cannot be read is still a line an operator wants to see.
 */
object ConsoleLineParser {
    /** Modern format, where the level is the tail of a thread tag. */
    private val THREAD_TAGGED = Regex("^\\[\\d{2}:\\d{2}:\\d{2}]\\s+\\[[^\\]]*?/([A-Za-z]+)]:")

    /** Older format, where the level sits next to the timestamp. */
    private val TIME_TAGGED = Regex("^\\[\\d{2}:\\d{2}:\\d{2}\\s+([A-Za-z]+)]:")

    /** CSI and OSC escape sequences, which a colourised server log is full of. */
    private val ANSI = Regex("\u001B(?:\\[[0-?]*[ -/]*[@-~]|][^\u0007\u001B]*(?:\u0007|\u001B\\\\))")

    fun stripAnsi(line: String): String = ANSI.replace(line, "")

    /** Bold, as a bit of [ColorSpan.flags]. */
    const val BOLD = 1

    /** Italic, as a bit of [ColorSpan.flags]. */
    const val ITALIC = 2

    /** Underline, as a bit of [ColorSpan.flags]. */
    const val UNDERLINE = 4

    /** Most spans one line carries; the rest of a rainbow is plain text. */
    const val MAX_SPANS = 64

    /**
     * How much escape code the file form of a line may keep (§2.4.21 B).
     *
     * The text is already capped at [MAX_MESSAGE_LENGTH]; this bounds what the colours add on top,
     * so a line in `console.log` can never be much longer than the line it shows.
     */
    const val MAX_SGR_CHARS = 8 * 1024

    /**
     * The 16 colours of `30–37` and `90–97`, in that order, tuned for the panel's dark console
     * rather than copied from a terminal: a pure `#0000ff` is unreadable on it. Also xterm-256
     * colours 0–15, in the same order.
     */
    val PALETTE = listOf(
        "#6e7681", "#ff7b72", "#7ee787", "#e3b341", "#79c0ff", "#d2a8ff", "#56d4dd", "#c9d1d9",
        "#8b949e", "#ffa198", "#aff5b4", "#f8e3a1", "#a5d6ff", "#e2c5ff", "#b3f0ff", "#f0f6fc"
    )

    /** The six steps of the xterm 6×6×6 colour cube (colours 16–231). */
    private val CUBE_LEVELS = intArrayOf(0, 95, 135, 175, 215, 255)

    /** An SGR token: CSI, parameter bytes only, final `m`. Anything else is not a colour change. */
    private val SGR = Regex("^\u001B\\[([0-?]*)m$")

    /** SGR parameters Pano understands: digits and separators, nothing private, no sub-parameters. */
    private val SGR_PARAMS = Regex("^[0-9;]*$")

    /**
     * A raw line split into its plain text, the colour spans over that text, and the form it is
     * kept in on disk (§2.4.21).
     *
     * [text] is exactly [stripAnsi] of the line, capped at [MAX_MESSAGE_LENGTH] — what `m` has
     * always been, so search, copy and every filter see the same string as before colour existed.
     * [fileForm] is the text with only the SGR codes that were applied put back in between, every
     * other escape dropped: what `console.log` stores, and what reading it back parses into the
     * same text and the same spans again.
     */
    data class Styled(val text: String, val spans: List<ColorSpan>, val fileForm: String)

    /**
     * Parses [raw] into text and colour spans (§2.4.21 A).
     *
     * One pass over the escape sequences [stripAnsi] would remove: the text between them is the
     * line, and each one that is an SGR updates the running style. A span is recorded for every run
     * of text whose style is not the default, merged with the one before it when nothing actually
     * changed, and at most [MAX_SPANS] of them are kept. Backgrounds, private modes and anything
     * else are read and ignored — a colour that cannot be drawn is not a reason to mangle a line.
     */
    fun styled(raw: String): Styled {
        val text = StringBuilder()
        val file = StringBuilder()
        val spans = ArrayList<ColorSpan>()

        var style = Style.DEFAULT
        var sgrChars = 0
        var position = 0

        fun addText(chunk: String) {
            val room = MAX_MESSAGE_LENGTH - text.length

            if (chunk.isEmpty() || room <= 0) {
                return
            }

            val kept = if (chunk.length > room) chunk.substring(0, room) else chunk
            val start = text.length

            text.append(kept)
            file.append(kept)

            if (style.isDefault) {
                return
            }

            val end = text.length
            val last = spans.lastOrNull()

            if (last != null && last.end == start && last.color == style.color && last.flags == style.flags) {
                spans[spans.lastIndex] = last.copy(end = end)
            } else if (spans.size < MAX_SPANS) {
                spans.add(ColorSpan(start, end, style.color, style.flags))
            }
        }

        for (match in ANSI.findAll(raw)) {
            addText(raw.substring(position, match.range.first))

            position = match.range.last + 1

            val params = sgrParams(match.value) ?: continue

            style = apply(style, params)

            // Kept only while it can still matter and fits the budget; past the text cap nothing
            // is left for it to colour.
            if (text.length < MAX_MESSAGE_LENGTH && sgrChars + match.value.length <= MAX_SGR_CHARS) {
                file.append(match.value)

                sgrChars += match.value.length
            }
        }

        addText(raw.substring(position))

        return Styled(text.toString(), spans, file.toString())
    }

    /**
     * The numbers of an SGR token, or null when [token] is not one Pano applies.
     *
     * Not an SGR at all (another CSI, an OSC) or a private or sub-parameter form (`?`, `:`): null,
     * stripped from the text with no change of style. Inside a real SGR an empty parameter is 0, as
     * the standard says — `ESC[m` is a reset and `1;;31` is bold, reset, red — and so is a run of
     * digits too long to be a number, which is the same rule the plugin's parser follows.
     */
    private fun sgrParams(token: String): List<Int>? {
        val body = SGR.find(token)?.groupValues?.get(1) ?: return null

        if (!SGR_PARAMS.matches(body)) {
            return null
        }

        return body.split(';').map { part -> part.toIntOrNull() ?: 0 }
    }

    /**
     * The style after one SGR's [params].
     *
     * An extended colour (`38;5;n`, `38;2;r;g;b`, and the same for background `48`) consumes its
     * arguments; one that is out of range changes nothing and the sequence carries on after it. A
     * missing argument or an unknown mode stops the sequence where it is: what came before it still
     * counts, what comes after it cannot be told apart from the arguments that are missing.
     */
    private fun apply(style: Style, params: List<Int>): Style {
        var color = style.color
        var flags = style.flags
        var index = 0

        while (index < params.size) {
            val param = params[index]

            when (param) {
                0 -> {
                    color = null
                    flags = 0
                }

                1 -> flags = flags or BOLD
                22 -> flags = flags and BOLD.inv()
                3 -> flags = flags or ITALIC
                23 -> flags = flags and ITALIC.inv()
                4 -> flags = flags or UNDERLINE
                24 -> flags = flags and UNDERLINE.inv()
                in 30..37 -> color = PALETTE[param - 30]
                in 90..97 -> color = PALETTE[8 + param - 90]
                39 -> color = null

                38, 48 -> {
                    val extended = extendedColor(params, index) ?: break

                    // A background is read so that its arguments are not taken for codes, and
                    // then dropped: the panel draws one background, its own.
                    if (param == 38 && extended.color != null) {
                        color = extended.color
                    }

                    index += extended.consumed

                    continue
                }

                // 40–47, 49, 100–107 and every other code: read, ignored.
                else -> Unit
            }

            index++
        }

        return Style(color, flags)
    }

    /**
     * `38`/`48` at [index]: the colour it names (null when out of range) and how many parameters it
     * used including itself, or null when an argument is missing or the mode is unknown.
     */
    private fun extendedColor(params: List<Int>, index: Int): Extended? {
        return when (params.getOrNull(index + 1)) {
            5 -> {
                val colour = params.getOrNull(index + 2) ?: return null

                Extended(colour.takeIf { it in 0..255 }?.let { xterm256(it) }, 3)
            }

            2 -> {
                if (index + 4 >= params.size) {
                    return null
                }

                val red = params[index + 2]
                val green = params[index + 3]
                val blue = params[index + 4]

                val inRange = red in 0..255 && green in 0..255 && blue in 0..255

                Extended(if (inRange) hex(red, green, blue) else null, 5)
            }

            else -> null
        }
    }

    private class Extended(val color: String?, val consumed: Int)

    /** An xterm-256 colour as `#rrggbb`: the palette, the 6×6×6 cube, then 24 greys. */
    fun xterm256(index: Int): String = when {
        index < 16 -> PALETTE[index]

        index < 232 -> {
            val cube = index - 16

            hex(CUBE_LEVELS[cube / 36], CUBE_LEVELS[(cube / 6) % 6], CUBE_LEVELS[cube % 6])
        }

        else -> (8 + 10 * (index - 232)).let { grey -> hex(grey, grey, grey) }
    }

    private fun hex(red: Int, green: Int, blue: Int): String = "#%02x%02x%02x".format(red, green, blue)

    /** The running SGR state: a foreground colour, or null for the default, and the flags. */
    private data class Style(val color: String?, val flags: Int) {
        val isDefault get() = color == null && flags == 0

        companion object {
            val DEFAULT = Style(null, 0)
        }
    }

    /**
     * The level [line] announces, or [fallback] when it announces none.
     *
     * [fallback] exists for stderr: a line the JVM wrote there carries no Minecraft log prefix at
     * all, and treating a stack trace as INFO would bury exactly the output someone is looking for.
     */
    fun parseLevel(line: String, fallback: ConsoleLevel = ConsoleLevel.INFO): ConsoleLevel {
        val stripped = stripAnsi(line)

        val match = THREAD_TAGGED.find(stripped) ?: TIME_TAGGED.find(stripped) ?: return fallback

        return normalise(match.groupValues[1]) ?: fallback
    }

    /** Folds a platform level name onto the five wire levels, or null when it is not one. */
    fun normalise(name: String): ConsoleLevel? = when (name.uppercase()) {
        "TRACE", "FINEST", "FINER" -> ConsoleLevel.TRACE
        "DEBUG", "FINE", "CONFIG" -> ConsoleLevel.DEBUG
        "INFO" -> ConsoleLevel.INFO
        "WARN", "WARNING" -> ConsoleLevel.WARN
        "ERROR", "SEVERE", "FATAL" -> ConsoleLevel.ERROR
        else -> null
    }

    /**
     * One raw pipe line turned into the wire shape, truncated to the protocol's per-line cap.
     *
     * `m` is the plain text exactly as it has always been; the colours ride alongside as `c`
     * (§2.4.21 A). [styled] is taken when the caller already parsed the line, so the stdout reader
     * does the work once for the console and the file alike.
     */
    fun toLine(
        raw: String,
        timestamp: Long,
        fallback: ConsoleLevel = ConsoleLevel.INFO,
        styled: Styled = styled(raw)
    ): ConsoleLine {
        val level = parseLevel(raw, fallback)

        return ConsoleLine(
            t = timestamp,
            l = level.name,
            m = styled.text,
            c = styled.spans.takeIf { it.isNotEmpty() }
        )
    }

    /** Same cap Pano and the Minecraft plugin enforce, so nothing is silently cut twice. */
    const val MAX_MESSAGE_LENGTH = 4 * 1024
}
