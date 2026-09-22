package com.panomc.platform.server.event

import com.panomc.platform.annotation.Event
import com.panomc.platform.db.model.Server
import com.panomc.platform.panel.PanelRealtimeHub
import com.panomc.platform.server.ServerEvent
import com.panomc.platform.server.ServerEventResponse
import com.panomc.platform.server.ServerManager
import com.panomc.platform.server.console.ConsoleSpans
import com.panomc.platform.server.dto.ConsoleLineData
import com.panomc.platform.server.event.request.ConsoleLinesEventRequest

/**
 * Receives a batch of console lines from a connected server (`CONSOLE_LINES`).
 *
 * The batch is stored in that server's ring buffer, so a panel opening the console page later
 * still sees the recent past, and fanned out to everyone watching it right now. Nothing here
 * renders or escapes the text: the raw line is what gets stored and forwarded, and turning it into
 * markup is the panel's job.
 *
 * Unless the node is the active source for this server, in which case the batch is dropped whole.
 * A managed server reports its console twice — once from the node's stdout pipe, once from this
 * plugin — and the two are the same lines, so forwarding both is not extra information, it is a
 * console printed in duplicate. The plugin is normally told to stop streaming before a batch like
 * this can arrive; this is the guard for the window in between, and for a plugin that ignores the
 * instruction.
 */
@Event
class ConsoleLinesEvent(
    private val serverManager: ServerManager,
    private val panelRealtimeHub: PanelRealtimeHub
) : ServerEvent<ConsoleLinesEventRequest, ServerEventResponse>() {
    override suspend fun handle(request: ConsoleLinesEventRequest, server: Server): ServerEventResponse? {
        val now = System.currentTimeMillis()

        // The payload comes from a plugin, so it is treated as untrusted input: an oversized
        // batch, an unknown level or a multi-megabyte line must cost a bounded amount of memory.
        val lines = (request.lines ?: emptyList())
            .take(MAX_LINES_PER_BATCH)
            .map { sanitize(it, now) }

        val dropped = (request.dropped ?: 0L).coerceAtLeast(0L)

        if (lines.isEmpty() && dropped == 0L) {
            return null
        }

        if (panelRealtimeHub.activeConsoleSource(server) != ConsoleLineData.SRC_PLUGIN) {
            return null
        }

        serverManager.getConsoleBuffer(server.id).add(lines, dropped)

        panelRealtimeHub.pushConsoleLines(server.id, lines, dropped)

        return null
    }

    // Gson fills missing fields with the data class defaults, but an explicit JSON null lands in
    // the field regardless of the Kotlin type, so the nulls are handled here rather than trusted.
    @Suppress("SENSELESS_COMPARISON")
    private fun sanitize(line: ConsoleLineData, now: Long): ConsoleLineData {
        val rawMessage = if (line.m == null) "" else line.m
        val rawLevel = if (line.l == null) ConsoleLineData.UNKNOWN_LEVEL else line.l.uppercase()

        val message = if (rawMessage.length > ConsoleLineData.MAX_MESSAGE_LENGTH)
            rawMessage.substring(0, ConsoleLineData.MAX_MESSAGE_LENGTH)
        else
            rawMessage

        return ConsoleLineData(
            t = if (line.t <= 0) now else line.t,
            l = if (rawLevel in ConsoleLineData.KNOWN_LEVELS) rawLevel else ConsoleLineData.UNKNOWN_LEVEL,
            m = message,
            // Forced, never taken from the payload: the source label is Pano's statement about
            // which stream a line arrived on, so a plugin cannot pass itself off as the node.
            src = ConsoleLineData.SRC_PLUGIN,
            // Judged against the text as Pano keeps it, so a span can never point past it (§2.4.21 D).
            c = ConsoleSpans.validate(line.c, message)
        )
    }

    companion object {
        // The plugin batches every 250 ms with a hard cap of 500 lines per second, so anything
        // beyond this in a single batch is a broken or hostile sender.
        private const val MAX_LINES_PER_BATCH = 500
    }
}
