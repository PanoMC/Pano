package com.panomc.platform.server.event

import com.panomc.platform.annotation.Event
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.Server
import com.panomc.platform.server.ServerEvent
import com.panomc.platform.server.ServerEventResponse
import com.panomc.platform.server.ServerStatus
import com.panomc.platform.server.event.request.OnServerConnectEventRequest

@Event
class OnServerConnectEvent(private val databaseManager: DatabaseManager) : ServerEvent<OnServerConnectEventRequest, ServerEventResponse>() {
    override suspend fun handle(request: OnServerConnectEventRequest, server: Server): ServerEventResponse? {
        val sqlClient = databaseManager.getSqlClient()

        server.name = request.serverName
        server.motd = request.motd ?: ""
        server.host = request.host
        server.port = request.port
        server.playerCount = request.playerCount
        server.maxPlayerCount = request.maxPlayerCount
        server.type = request.serverType
        server.version = request.serverVersion
        server.favicon = request.favicon ?: ""
        server.status = ServerStatus.ONLINE
        server.startTime = request.startTime

        databaseManager.serverDao.update(server, sqlClient)

        return null
    }
}