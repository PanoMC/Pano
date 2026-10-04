package com.panomc.node.console

/**
 * One line of a managed server's output, already normalised for the wire.
 *
 * The field names are the protocol: `t` epoch millis, `l` level, `m` text. Pano adds the `src`
 * flag itself, so the node never sends one -- a node that could label its own lines could claim to
 * be the plugin stream.
 */
data class ConsoleLine(
    val t: Long,
    val l: String,
    val m: String,
    /**
     * Colour spans over [m] (§2.4.21 A), or null when the line has none — which is every line of a
     * plain log file and most lines of a busy server. Sent as `c`, omitted when null.
     */
    val c: List<ColorSpan>? = null
)

/**
 * One styled run of a console line: `[start, end)` in UTF-16 chars of the line's plain text.
 *
 * [color] is `#rrggbb` in lower case or null for the default foreground; [flags] is a bitmask of
 * [ConsoleLineParser.BOLD], [ConsoleLineParser.ITALIC] and [ConsoleLineParser.UNDERLINE]. A span
 * only exists where the style differs from the default, so a null colour always comes with a flag.
 */
data class ColorSpan(
    val start: Int,
    val end: Int,
    val color: String?,
    val flags: Int
)

/** The five levels the panel renders. Every platform level is folded onto one of them. */
enum class ConsoleLevel {
    TRACE,
    DEBUG,
    INFO,
    WARN,
    ERROR
}
