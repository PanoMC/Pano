package com.panomc.platform.server.event

import com.panomc.platform.annotation.Event
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.Server
import com.panomc.platform.db.model.ServerPlayer
import com.panomc.platform.db.model.User
import com.panomc.platform.server.ServerEvent
import com.panomc.platform.server.ServerEventResponse
import com.panomc.platform.server.event.request.OnPlayerJoinEventRequest

@Event
class OnPlayerJoinEvent(private val databaseManager: DatabaseManager) : ServerEvent<OnPlayerJoinEventRequest, ServerEventResponse>() {
    override suspend fun handle(request: OnPlayerJoinEventRequest, server: Server): ServerEventResponse? {
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

        val userId = databaseManager.userDao.getUserIdFromUsername(player.username, sqlClient)

        if (userId != null) {
            databaseManager.userDao.updateLastLoginDate(userId, sqlClient)
        } else {
            // Create user
            val newUser = User(
                username = player.username,
                email = null,
                registeredIp = player.ipAddress,
                mcUuid = player.uuid.toString()
            )
            
            databaseManager.userDao.add(newUser, null, sqlClient, false)
        }

        return null
    }
}