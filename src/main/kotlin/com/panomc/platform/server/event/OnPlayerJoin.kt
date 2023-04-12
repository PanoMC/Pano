package com.panomc.platform.server.event

import com.panomc.platform.annotation.Event
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.Server
import com.panomc.platform.db.model.ServerPlayer
import com.panomc.platform.server.ServerEvent
import com.panomc.platform.server.ServerEventListener
import io.vertx.core.json.JsonObject
import java.util.*

@Event
class OnPlayerJoin(private val databaseManager: DatabaseManager) : ServerEventListener {
    override val serverEvent: ServerEvent = ServerEvent.ON_PLAYER_JOIN

    override suspend fun handle(body: JsonObject, server: Server) {
        val player = body.getJsonObject("player")
        val playerCount = body.getInteger("playerCount")

        val sqlClient = databaseManager.getSqlClient()

        val serverPlayer = ServerPlayer(
            uuid = UUID.fromString(player.getString("uuid")),
            username = player.getString("username"),
            ping = player.getLong("ping"),
            serverId = server.id,
            loginTime = player.getLong("loginTime")
        )

        databaseManager.serverPlayerDao.add(serverPlayer, sqlClient)
        databaseManager.serverDao.updatePlayerCountById(server.id, playerCount, sqlClient)
    }
}