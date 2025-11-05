package com.panomc.platform.server.event

import com.panomc.platform.annotation.Event
import com.panomc.platform.db.model.Server
import com.panomc.platform.server.ServerEvent
import com.panomc.platform.server.event.request.PingEventRequest
import com.panomc.platform.server.response.PongServerEventResponse

@Event
class PingEvent : ServerEvent<PingEventRequest, PongServerEventResponse>() {
    override suspend fun handle(request: PingEventRequest, server: Server): PongServerEventResponse {
        return PongServerEventResponse( "pong")
    }
}