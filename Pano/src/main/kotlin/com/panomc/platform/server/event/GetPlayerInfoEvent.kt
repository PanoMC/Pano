package com.panomc.platform.server.event

import com.panomc.platform.annotation.Event
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.Server
import com.panomc.platform.server.ServerEvent
import com.panomc.platform.server.event.request.GetPlayerInfoEventRequest
import com.panomc.platform.server.response.GetPlayerInfoEventResponse
import com.panomc.platform.util.BanUtil

@Event
class GetPlayerInfoEvent(
    private val databaseManager: DatabaseManager
) : ServerEvent<GetPlayerInfoEventRequest, GetPlayerInfoEventResponse>() {
    override suspend fun handle(request: GetPlayerInfoEventRequest, server: Server): GetPlayerInfoEventResponse {
        val sqlClient = databaseManager.getSqlClient()

        val userId = databaseManager.userDao.getUserIdFromUsername(request.username, sqlClient)
        val player = if (userId == null) null else databaseManager.userDao.getById(userId, sqlClient)

        val registered = userId != null
        val banned = if (player == null) false else BanUtil.isBanned(player)
        val banReason = player?.banMessage
        val bannedUntil = player?.bannedUntil
        val verified = player?.emailVerified ?: false
        val email = player?.email
        val locale = player?.localeCode

        return GetPlayerInfoEventResponse(
            registered,
            banned,
            banReason,
            bannedUntil,
            verified,
            email?.ifBlank { null },
            locale
        )
    }
}