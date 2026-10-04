package com.panomc.platform.server.event

import com.panomc.platform.annotation.Event
import com.panomc.platform.db.model.Server
import com.panomc.platform.server.ServerEvent
import com.panomc.platform.server.ServerEventResponse
import com.panomc.platform.server.ServerManager
import com.panomc.platform.server.event.request.ConsoleSearchResultEventRequest

/**
 * Receives a plugin's answer to `CONSOLE_SEARCH`.
 *
 * Nothing but a hand-over, exactly like [ConsoleHistoryResultEvent]: the page belongs to the
 * endpoint suspended on the reply. A late answer to a request that already timed out, or one with
 * somebody else's `eventId`, is dropped without a word.
 */
@Event
class ConsoleSearchResultEvent(
    private val serverManager: ServerManager
) : ServerEvent<ConsoleSearchResultEventRequest, ServerEventResponse>() {
    override suspend fun handle(request: ConsoleSearchResultEventRequest, server: Server): ServerEventResponse? {
        serverManager.completeRequest(server.id, request.eventId, request)

        return null
    }
}
