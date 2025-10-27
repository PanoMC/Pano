package com.panomc.platform.server.event

import com.panomc.platform.annotation.Event
import com.panomc.platform.db.model.Server
import com.panomc.platform.server.PlatformMessage
import com.panomc.platform.server.ServerEvent
import com.panomc.platform.server.ServerManager
import com.panomc.platform.server.event.request.PingEventRequest
import com.panomc.platform.server.response.PongServerEventResponse

@Event
class PingEvent(private val serverManager: ServerManager) : ServerEvent<PingEventRequest>() {
    override suspend fun handle(request: PingEventRequest, server: Server): PlatformMessage? {
        val message = PongServerEventResponse(request.eventId, "pong")

        serverManager.sendMessage(message, server)

        return null
    }
}