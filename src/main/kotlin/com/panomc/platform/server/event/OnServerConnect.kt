package com.panomc.platform.server.event

import com.panomc.platform.annotation.Event
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.Server
import com.panomc.platform.server.ServerEvent
import com.panomc.platform.server.ServerEventListener
import com.panomc.platform.server.ServerStatus
import com.panomc.platform.server.ServerType
import io.vertx.core.json.JsonObject

@Event
class OnServerConnect(private val databaseManager: DatabaseManager) : ServerEventListener {
    override val serverEvent: ServerEvent = ServerEvent.ON_SERVER_CONNECT

    override suspend fun handle(body: JsonObject, server: Server) {
        val sqlClient = databaseManager.getSqlClient()

        databaseManager.serverDao.updateById(
            server.id,
            body.getString("serverName"),
            body.getString("motd") ?: "",
            body.getString("host"),
            body.getInteger("port"),
            body.getLong("playerCount"),
            body.getLong("maxPlayerCount"),
            ServerType.valueOf(body.getString("serverType")),
            body.getString("serverVersion"),
            body.getString("favicon") ?: "",
            ServerStatus.ONLINE,
            body.getLong("startTime"),
            sqlClient
        )
    }
}