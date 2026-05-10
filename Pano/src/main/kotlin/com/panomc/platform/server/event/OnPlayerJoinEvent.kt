package com.panomc.platform.server.event

import com.panomc.platform.annotation.Event
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.Server
import com.panomc.platform.db.model.ServerPlayer
import com.panomc.platform.db.model.User
import com.panomc.platform.panel.PanelRealtimeHub
import com.panomc.platform.server.ServerEvent
import com.panomc.platform.server.ServerEventResponse
import com.panomc.platform.server.event.request.OnPlayerJoinEventRequest
import io.vertx.mysqlclient.MySQLException

@Event
class OnPlayerJoinEvent(
    private val databaseManager: DatabaseManager,
    private val panelRealtimeHub: PanelRealtimeHub
) : ServerEvent<OnPlayerJoinEventRequest, ServerEventResponse>() {
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

        val user = databaseManager.userDao.getByUsername(player.username, sqlClient)
        val playerUuid = player.uuid.toString()

        if (user != null) {
            databaseManager.userDao.updateLastLoginDate(user.id, sqlClient)
            if (user.mcUuid != playerUuid) {
                databaseManager.userDao.setMcUuidById(user.id, playerUuid, sqlClient)
            }
        } else {
            // Create user
            val newUser = User(
                username = player.username,
                email = null,
                registeredIp = player.ipAddress,
                mcUuid = playerUuid
            )

            try {
                databaseManager.userDao.add(newUser, null, sqlClient, false)
            } catch (e: MySQLException) {
                // Another flow might have inserted the same username concurrently.
                if (e.errorCode == 1062) {
                    databaseManager.userDao.getUserIdFromUsername(player.username, sqlClient)?.let {
                        databaseManager.userDao.updateLastLoginDate(it, sqlClient)
                    }
                } else {
                    throw e
                }
            }
        }

        panelRealtimeHub.notifyServerUpdated(server.id)

        return null
    }
}