package com.panomc.platform.server.event

import com.panomc.platform.annotation.Event
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.Server
import com.panomc.platform.server.ServerEvent
import com.panomc.platform.server.ServerEventListener
import io.vertx.core.json.JsonObject

@Event
class OnPlayerDisconnect(private val databaseManager: DatabaseManager) : ServerEventListener {
    override val serverEvent: ServerEvent = ServerEvent.ON_PLAYER_DISCONNECT

    override suspend fun handle(body: JsonObject, server: Server) {
        val player = body.getJsonObject("player")
        val playerCount = body.getInteger("playerCount")

        val sqlClient = databaseManager.getSqlClient()

        databaseManager.serverPlayerDao.deleteByUsernameAndServerId(
            player.getString("username"),
            server.id,
            sqlClient
        )

        databaseManager.serverDao.updatePlayerCountById(server.id, playerCount, sqlClient)
    }
}