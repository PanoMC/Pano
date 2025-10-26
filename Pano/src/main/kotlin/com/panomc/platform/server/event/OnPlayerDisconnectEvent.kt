package com.panomc.platform.server.event

import com.panomc.platform.annotation.Event
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.Server
import com.panomc.platform.server.ServerEvent
import com.panomc.platform.server.event.request.OnPlayerDisconnectEventRequest

@Event
class OnPlayerDisconnectEvent(private val databaseManager: DatabaseManager) :
    ServerEvent<OnPlayerDisconnectEventRequest>() {
    override suspend fun handle(request: OnPlayerDisconnectEventRequest, server: Server) {
        val player = request.player
        val sqlClient = databaseManager.getSqlClient()

        databaseManager.serverPlayerDao.deleteByUsernameAndServerId(
            player.username,
            server.id,
            sqlClient
        )

        databaseManager.serverDao.updatePlayerCountById(server.id, request.playerCount, sqlClient)
    }
}