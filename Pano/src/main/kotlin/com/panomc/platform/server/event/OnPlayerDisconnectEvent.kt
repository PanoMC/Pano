package com.panomc.platform.server.event

import com.panomc.platform.annotation.Event
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.Server
import com.panomc.platform.panel.PanelRealtimeHub
import com.panomc.platform.server.ServerEvent
import com.panomc.platform.server.ServerEventResponse
import com.panomc.platform.server.event.request.OnPlayerDisconnectEventRequest

@Event
class OnPlayerDisconnectEvent(
    private val databaseManager: DatabaseManager,
    private val panelRealtimeHub: PanelRealtimeHub
) :
    ServerEvent<OnPlayerDisconnectEventRequest, ServerEventResponse>() {
    override suspend fun handle(request: OnPlayerDisconnectEventRequest, server: Server): ServerEventResponse? {
        val player = request.player
        val sqlClient = databaseManager.getSqlClient()

        databaseManager.serverPlayerDao.deleteByUsernameAndServerId(
            player.username,
            server.id,
            sqlClient
        )

        databaseManager.serverDao.updatePlayerCountById(server.id, request.playerCount, sqlClient)

        panelRealtimeHub.notifyServerUpdated(server.id)

        return null
    }
}