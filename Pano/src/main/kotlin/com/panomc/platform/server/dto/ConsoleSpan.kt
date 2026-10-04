package com.panomc.platform.server.dto

/**
 * One colour span of a console line (§2.4.21 A): `[start, end)` in UTF-16 chars of the line's `m`.
 *
 * [color] is `#rrggbb` in lower case or null for the default foreground; [flags] is a bitmask —
 * `1` bold, `2` italic, `4` underline. On the wire it is the compact `[start, end, color, flags]`,
 * because a busy server sends hundreds of lines a second and most of them carry several of these.
 */
data class ConsoleSpan(
    val start: Int,
    val end: Int,
    val color: String?,
    val flags: Int
)
