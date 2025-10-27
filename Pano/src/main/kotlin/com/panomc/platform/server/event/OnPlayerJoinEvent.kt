package com.panomc.platform.server.event

import com.panomc.platform.annotation.Event
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.Server
import com.panomc.platform.db.model.ServerPlayer
import com.panomc.platform.server.PlatformMessage
import com.panomc.platform.server.ServerEvent
import com.panomc.platform.server.event.request.OnPlayerJoinEventRequest

@Event
class OnPlayerJoinEvent(private val databaseManager: DatabaseManager) : ServerEvent<OnPlayerJoinEventRequest>() {
    override suspend fun handle(request: OnPlayerJoinEventRequest, server: Server): PlatformMessage? {
        val player = request.player

        val sqlClient = databaseManager.getSqlClient()

        val serverPlayer = ServerPlayer(
            uuid = player.uuid,
            username = player.username,
            ping = player.ping,
            serverId = server.id,
            loginTime = player.loginTime
        )

        databaseManager.serverPlayerDao.add(serverPlayer, sqlClient)
        databaseManager.serverDao.updatePlayerCountById(server.id, request.playerCount, sqlClient)

        return null
    }
}