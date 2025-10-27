package com.panomc.platform.server.event

import com.panomc.platform.annotation.Event
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.Server
import com.panomc.platform.server.PlatformMessage
import com.panomc.platform.server.ServerEvent
import com.panomc.platform.server.ServerStatus
import com.panomc.platform.server.event.request.OnServerConnectEventRequest

@Event
class OnServerConnectEvent(private val databaseManager: DatabaseManager) : ServerEvent<OnServerConnectEventRequest>() {
    override suspend fun handle(request: OnServerConnectEventRequest, server: Server): PlatformMessage? {
        val sqlClient = databaseManager.getSqlClient()

        databaseManager.serverDao.updateById(
            server.id,
            request.serverName,
            request.motd ?: "",
            request.host,
            request.port,
            request.playerCount,
            request.maxPlayerCount,
            request.serverType,
            request.serverVersion,
            request.favicon ?: "",
            ServerStatus.ONLINE,
            request.startTime,
            sqlClient
        )

        return null
    }
}