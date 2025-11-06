package com.panomc.platform.server.event

import com.panomc.platform.annotation.Event
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.Server
import com.panomc.platform.server.ServerEvent
import com.panomc.platform.server.event.request.IsPlayerRegisteredEventRequest
import com.panomc.platform.server.response.GetPlayerInfoEventResponse

@Event
class GetPlayerInfoEvent(
    private val databaseManager: DatabaseManager
) : ServerEvent<IsPlayerRegisteredEventRequest, GetPlayerInfoEventResponse>() {
    override suspend fun handle(request: IsPlayerRegisteredEventRequest, server: Server): GetPlayerInfoEventResponse {
        val sqlClient = databaseManager.getSqlClient()

        val userId = databaseManager.userDao.getUserIdFromUsername(request.username, sqlClient)

        val registered = if (userId == null) false else databaseManager.userDao.existsById(userId, sqlClient)
        val banned = if (userId == null) false else databaseManager.userDao.isBanned(userId, sqlClient)
        val verified = if (userId == null) false else databaseManager.userDao.isEmailVerifiedById(userId, sqlClient)
        val pendingEmail = if (userId == null) null else databaseManager.userDao.getPendingEmailById(userId, sqlClient)
        val locale = if (userId == null) null else databaseManager.userDao.getLocaleCodeById(userId, sqlClient)

        return GetPlayerInfoEventResponse(
            registered,
            banned,
            verified,
            pendingEmail?.ifBlank { null },
            locale
        )
    }
}