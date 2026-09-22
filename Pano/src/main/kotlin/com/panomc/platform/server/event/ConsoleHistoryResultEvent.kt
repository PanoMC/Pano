package com.panomc.platform.server.event

import com.panomc.platform.annotation.Event
import com.panomc.platform.db.model.Server
import com.panomc.platform.server.ServerEvent
import com.panomc.platform.server.ServerEventResponse
import com.panomc.platform.server.ServerManager
import com.panomc.platform.server.event.request.ConsoleHistoryResultEventRequest

/**
 * Receives a plugin's answer to `CONSOLE_HISTORY`.
 *
 * Unlike every other event here this one has no work of its own: the page belongs to the endpoint
 * that asked for it, which is suspended on the reply, so all that happens is handing it over. A
 * reply nobody is waiting for — a late answer whose request already timed out, or an `eventId`
 * from another server — is dropped without a word, because a plugin cannot be stopped from
 * sending one and neither case is worth a log line.
 */
@Event
class ConsoleHistoryResultEvent(
    private val serverManager: ServerManager
) : ServerEvent<ConsoleHistoryResultEventRequest, ServerEventResponse>() {
    override suspend fun handle(request: ConsoleHistoryResultEventRequest, server: Server): ServerEventResponse? {
        serverManager.completeRequest(server.id, request.eventId, request)

        return null
    }
}
