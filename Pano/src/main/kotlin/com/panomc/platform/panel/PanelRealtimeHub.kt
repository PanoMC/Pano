package com.panomc.platform.panel

import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.Server
import io.vertx.core.Vertx
import io.vertx.core.http.ServerWebSocket
import io.vertx.core.json.JsonObject
import io.vertx.kotlin.coroutines.dispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.context.annotation.Lazy
import org.springframework.context.annotation.Scope
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap

/**
 * Pushes live server data to clients that can manage servers and subscribe, and
 * [panelNotificationRefresh] nudges for the panel notification list/count.
 */
@Lazy
@Component
@Scope(value = ConfigurableBeanFactory.SCOPE_SINGLETON)
class PanelRealtimeHub(
    private val databaseManager: DatabaseManager,
    private val vertx: Vertx
) {
    private data class ClientSession(
        val userId: Long,
        val canManageServers: Boolean,
        var subscribeNotifications: Boolean = true,
        var subscribeServers: Boolean = false,
        var subscribeServerId: Long? = null
    )

    private val sessions = ConcurrentHashMap<ServerWebSocket, ClientSession>()

    fun register(socket: ServerWebSocket, userId: Long, canManageServers: Boolean) {
        sessions[socket] = ClientSession(
            userId = userId,
            canManageServers = canManageServers
        )
    }

    fun unregister(socket: ServerWebSocket) {
        sessions.remove(socket)
    }

    fun applyClientConfig(socket: ServerWebSocket, text: String) {
        val body = try {
            JsonObject(text)
        } catch (_: Exception) {
            return
        }
        val s = sessions[socket] ?: return
        if (body.containsKey("subscribeNotifications")) {
            s.subscribeNotifications = body.getBoolean("subscribeNotifications", true)
        }
        if (s.canManageServers) {
            if (body.containsKey("subscribeServers")) {
                s.subscribeServers = body.getBoolean("subscribeServers", false)
            }
            if (body.containsKey("subscribeServerId")) {
                val v = body.getValue("subscribeServerId")
                s.subscribeServerId = if (v == null) {
                    null
                } else {
                    (v as Number).toLong()
                }
            }
        } else {
            s.subscribeServers = false
            s.subscribeServerId = null
        }
    }

    /**
     * Tells the user's open panel session(s) to re-fetch notification state over HTTP.
     */
    fun notifyPanelNotificationRefresh(userId: Long) {
        val text = JsonObject()
            .put("type", "panelNotificationRefresh")
            .encode()
        for ((ws, s) in sessions.toList()) {
            if (ws.isClosed) {
                sessions.remove(ws)
                continue
            }
            if (s.userId != userId || !s.subscribeNotifications) {
                continue
            }
            try {
                ws.writeTextMessage(text)
            } catch (_: Exception) {
                sessions.remove(ws)
            }
        }
    }

    /**
     * Notifies all subscribers that care about this server. Loads latest row from the database.
     */
    fun notifyServerUpdated(serverId: Long) {
        CoroutineScope(vertx.dispatcher()).launch {
            val sqlClient = databaseManager.getSqlClient()
            val server = databaseManager.serverDao.getById(serverId, sqlClient) ?: return@launch
            if (!server.permissionGranted) {
                return@launch
            }
            val text = JsonObject()
                .put("type", "server")
                .put("server", serverToPublicJsonObject(server))
                .encode()
            writeToRelevantSubscribers(text, serverId)
        }
    }

    fun notifyServerRemoved(serverId: Long) {
        val text = JsonObject()
            .put("type", "serverRemoved")
            .put("serverId", serverId)
            .encode()
        writeToRelevantSubscribers(text, serverId)
    }

    private fun writeToRelevantSubscribers(message: String, serverId: Long) {
        for ((ws, s) in sessions.toList()) {
            if (ws.isClosed) {
                sessions.remove(ws)
                continue
            }
            if (!s.canManageServers) {
                continue
            }
            val want = s.subscribeServers || s.subscribeServerId == serverId
            if (!want) {
                continue
            }
            try {
                ws.writeTextMessage(message)
            } catch (_: Exception) {
                sessions.remove(ws)
            }
        }
    }

    private fun serverToPublicJsonObject(server: Server): JsonObject {
        val o = JsonObject(server.toJson())
        o.remove("aesKey")
        return o
    }
}
