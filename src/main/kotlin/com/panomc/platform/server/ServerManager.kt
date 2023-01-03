package com.panomc.platform.server

import com.panomc.platform.annotation.Event
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.Server
import io.vertx.core.http.ServerWebSocket
import io.vertx.core.json.JsonObject
import org.slf4j.Logger
import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.context.annotation.Lazy
import org.springframework.context.annotation.Scope
import org.springframework.stereotype.Component

@Lazy
@Component
@Scope(value = ConfigurableBeanFactory.SCOPE_SINGLETON)
class ServerManager(
    private val logger: Logger,
    private val databaseManager: DatabaseManager,
    private val applicationContext: AnnotationConfigApplicationContext
) {
    private val connectedServers = mutableMapOf<Server, ServerWebSocket>()

    private val eventListeners by lazy {
        val beans = applicationContext.getBeansWithAnnotation(Event::class.java)

        beans.filter { it.value is ServerEventListener }.map { it.value as ServerEventListener }
    }

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

        val serverEvent = ServerEvent.valueOf(event)

        eventListeners
            .filter {
                it.serverEvent == serverEvent
            }
            .forEach {
                it.handle(body, server)
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