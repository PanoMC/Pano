package com.panomc.platform.server.dto

import com.google.gson.annotations.JsonAdapter
import com.panomc.platform.server.console.ConsoleSpans

/**
 * One match of a deep console search as a plugin sends it: a [ConsoleLineData] on the wire plus
 * [f], the log file it was found in (`latest.log`, `2026-09-22-3.log.gz`, or `console`).
 *
 * Its own class rather than a field on [ConsoleLineData], because that one is also the shape of
 * every streamed line and a file name has no business travelling with those. Decoded as leniently
 * as a history line — a malformed `c` is null, a missing field its default — and sanitised before
 * anything of it reaches the panel.
 */
data class ConsoleSearchLineData(
    val t: Long? = null,
    val l: String? = null,
    val m: String? = null,
    @field:JsonAdapter(ConsoleSpans.GsonAdapter::class)
    val c: List<ConsoleSpan>? = null,
    val f: String? = null
)
