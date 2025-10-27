package com.panomc.platform.server.event

import com.panomc.platform.annotation.Event
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.Server
import com.panomc.platform.server.PlatformMessage
import com.panomc.platform.server.ServerEvent
import com.panomc.platform.server.event.request.IsPlayerRegisteredEventRequest
import com.panomc.platform.server.response.IsPlayerRegisteredEventResponse

@Event
class IsPlayerRegisteredEvent(
    private val databaseManager: DatabaseManager
) : ServerEvent<IsPlayerRegisteredEventRequest>() {
    override suspend fun handle(request: IsPlayerRegisteredEventRequest, server: Server): PlatformMessage {
        val sqlClient = databaseManager.getSqlClient()

        val registered = databaseManager.userDao.existsByUsername(request.username, sqlClient)

        return IsPlayerRegisteredEventResponse(request.eventId, registered)
    }
}