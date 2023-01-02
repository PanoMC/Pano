package com.panomc.platform.server

import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.Server
import io.vertx.core.buffer.Buffer
import io.vertx.core.http.ServerWebSocket
import org.slf4j.Logger

class ServerManager(private val logger: Logger, private val databaseManager: DatabaseManager) {
    private val connectedServers = mutableMapOf<Server, ServerWebSocket>()

    suspend fun init() {
        val sqlConnection = databaseManager.createConnection()

        val servers = databaseManager.serverDao.getAllByPermissionGranted(sqlConnection)

        servers.forEach { server ->
            databaseManager.serverDao.updateStatusById(server.id, ServerStatus.OFFLINE, sqlConnection)
        }

        databaseManager.closeConnection(sqlConnection)
    }

    fun onServerConnect(server: Server, serverWebSocket: ServerWebSocket) {
        connectedServers[server] = serverWebSocket

        logger.info("\"${server.name}\" Minecraft server is connected!")
    }

    fun onServerDisconnect(server: Server) {
        connectedServers.remove(server)

        logger.warn("\"${server.name}\" Minecraft server is disconnected!")
    }

    suspend fun onServerWrite(text: String, server: Server) {
        val body = JsonObject(text)
        val event = body.getString("event")

        if (!ServerEvent.values().any { it.name == event }) {
            return
        }

    }

    fun closeConnection(id: Long) {
        connectedServers
            .filter {
                it.key.id == id
            }
            .forEach {
                it.value.close()
            }
    }

    fun isConnected(id: Long) = connectedServers.filter { it.key.id == id }.isNotEmpty()

    fun getConnectedServers() = connectedServers.toMap()


}