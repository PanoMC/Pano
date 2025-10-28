package com.panomc.platform.server.event

import com.panomc.platform.annotation.Event
import com.panomc.platform.db.model.Server
import com.panomc.platform.server.PlatformMessage
import com.panomc.platform.server.ServerEvent
import com.panomc.platform.server.event.request.GetServerSettingsEventRequest
import com.panomc.platform.server.response.GetServerSettingsEventResponse

@Event
class GetServerSettingsEvent : ServerEvent<GetServerSettingsEventRequest>() {
    override suspend fun handle(request: GetServerSettingsEventRequest, server: Server): PlatformMessage {
        val settings = server.settings

        return GetServerSettingsEventResponse(
            request.eventId,
            settings.authIntegration,
            settings.banIntegration,
            settings.permissionIntegration
        )
    }
}