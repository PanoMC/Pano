package com.panomc.platform.server.event

import com.panomc.platform.annotation.Event
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.Server
import com.panomc.platform.model.Error
import com.panomc.platform.server.PlatformMessage
import com.panomc.platform.server.ServerEvent
import com.panomc.platform.server.event.request.PlayerAuthenticateEventRequest
import com.panomc.platform.server.response.PlayerAuthenticateEventResponse

@Event
class PlayerAuthenticateEvent(
    private val authProvider: AuthProvider,
    private val databaseManager: DatabaseManager
) : ServerEvent<PlayerAuthenticateEventRequest>() {
    override suspend fun handle(request: PlayerAuthenticateEventRequest, server: Server): PlatformMessage {
        val sqlClient = databaseManager.getSqlClient()

        var success = true

        try {
            authProvider.authenticate(request.username, request.password, sqlClient)
        } catch (_: Error) {
            success = false
        }

        return PlayerAuthenticateEventResponse(request.eventId, success)
    }
}