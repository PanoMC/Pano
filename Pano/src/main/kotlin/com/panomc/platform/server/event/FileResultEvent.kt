package com.panomc.platform.server.event

import com.panomc.platform.annotation.Event
import com.panomc.platform.db.model.Server
import com.panomc.platform.server.ServerEvent
import com.panomc.platform.server.ServerEventResponse
import com.panomc.platform.server.ServerManager
import com.panomc.platform.server.event.request.FileResultEventRequest

/**
 * Receives a plugin's answer to an agent-lite request (`FILE_RESULT`, §2.4.17 C).
 *
 * No work of its own, like the console history result: the endpoint that asked is suspended on
 * this reply, so all that happens is handing it over. A reply nobody is waiting for — a late
 * answer whose request already timed out, or an `eventId` belonging to another server — is dropped
 * without a word, because a plugin cannot be stopped from sending one.
 */
@Event
class FileResultEvent(
    private val serverManager: ServerManager
) : ServerEvent<FileResultEventRequest, ServerEventResponse>() {
    override suspend fun handle(request: FileResultEventRequest, server: Server): ServerEventResponse? {
        serverManager.completeRequest(server.id, request.eventId, request)

        return null
    }
}
