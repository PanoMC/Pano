package com.panomc.platform.server

import com.panomc.platform.db.model.Server
import io.vertx.core.buffer.Buffer
import io.vertx.core.http.ServerWebSocket
import org.slf4j.Logger

class ServerManager(private val logger: Logger) {
    private val connectedServers = mutableMapOf<Server, ServerWebSocket>()

    fun onServerConnect(server: Server, serverWebSocket: ServerWebSocket) {
        connectedServers[server] = serverWebSocket

        logger.info("\"${server.name}\" Minecraft server is connected!")
    }

    fun onServerDisconnect(server: Server) {
        connectedServers.remove(server)

        logger.warn("\"${server.name}\" Minecraft server is disconnected!")
    }

    fun onServerWrite(buffer: Buffer) {

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