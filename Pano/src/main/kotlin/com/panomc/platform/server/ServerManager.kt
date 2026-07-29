package com.panomc.platform.server

import com.google.gson.Gson
import com.panomc.platform.annotation.Event
import com.panomc.platform.config.ConfigManager
import com.panomc.platform.config.PanoConfig
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.Server
import com.panomc.platform.util.Aes256GcmUtil
import io.vertx.core.Vertx
import io.vertx.core.buffer.Buffer
import io.vertx.core.http.ServerWebSocket
import io.vertx.core.json.JsonObject
import org.slf4j.Logger
import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.context.annotation.Lazy
import org.springframework.context.annotation.Scope
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.SecretKey

// Resolved (and already-validated) heartbeat cadence, in milliseconds so it drops straight into
// vertx.setPeriodic and elapsed-time comparisons. Mirrors the client-side
// PlatformManager.HeartbeatSettings in pano-mc-plugin.
private data class HeartbeatSettings(val intervalMs: Long, val timeoutMs: Long)

@Lazy
@Component
@Scope(value = ConfigurableBeanFactory.SCOPE_SINGLETON)
class ServerManager(
    private val logger: Logger,
    private val databaseManager: DatabaseManager,
    private val applicationContext: AnnotationConfigApplicationContext,
    private val vertx: Vertx,
    private val configManager: ConfigManager
) {
    // ConcurrentHashMap (not a plain mutableMapOf): connectedServers/serverSecretKeyMap/lastPongAt
    // are read and mutated from coroutines resumed on different connection handlers (see
    // onServerConnect/onServerDisconnect) while the shared heartbeat sweep below concurrently
    // iterates connectedServers on a timer callback. A plain HashMap would risk a
    // ConcurrentModificationException the moment a server (dis)connects mid-sweep; ConcurrentHashMap's
    // weakly-consistent iterators make that safe without a lock. Same pattern already used by
    // PanelRealtimeHub for its ServerWebSocket -> session map.
    internal val connectedServers = ConcurrentHashMap<Server, ServerWebSocket>()
    private val serverSecretKeyMap = ConcurrentHashMap<Server, SecretKey>()

    // Last time each connected Minecraft server answered a heartbeat ping with a pong, keyed the
    // same way as connectedServers. Populated on connect (so a fresh connection gets a full
    // timeout window before the first sweep can judge it) and torn down in onServerDisconnect so
    // nothing leaks when a server goes away or is closed for timing out.
    private val lastPongAtMap = ConcurrentHashMap<Server, Long>()

    private val eventListeners by lazy {
        val beans = applicationContext.getBeansWithAnnotation(Event::class.java)

        beans.filter { it.value is ServerEvent<*, *> }.map { it.value as ServerEvent<*, *> }.toMutableList()
    }

    fun registerEvent(event: ServerEvent<*, *>) {
        eventListeners.add(event)
    }

    fun unregisterEvent(event: ServerEvent<*, *>) {
        eventListeners.remove(event)
    }

    suspend fun init() {
        val sqlClient = databaseManager.getSqlClient()

        val servers = databaseManager.serverDao.getAllByPermissionGranted(sqlClient)

        servers.forEach { server ->
            databaseManager.serverDao.updateServerForOfflineById(server.id, sqlClient)
        }

        startHeartbeatSweep()
    }

    fun onServerConnect(server: Server, serverWebSocket: ServerWebSocket) {
        connectedServers[server] = serverWebSocket
        serverSecretKeyMap[server] = Aes256GcmUtil.base64ToSecretKey(server.aesKey)

        // Fresh connection: give it a full timeout window before the sweep judges it, rather than
        // treating "no pong yet" as already overdue.
        lastPongAtMap[server] = System.currentTimeMillis()

        // Vert.x auto-replies to an inbound ping and never surfaces it to application code (no
        // pingHandler exists on WebSocketBase) — so the client's own heartbeat pings are invisible
        // here. Liveness has to be driven from this side: we send our own ping in the heartbeat
        // sweep and track the answering pong here.
        serverWebSocket.pongHandler {
            // Socket-identity guard, same pattern as the client's PlatformManager: a pong for a
            // socket that has since been superseded by a newer connection for the same server ID
            // (the Minecraft server restarted and reconnected) must not refresh the NEW
            // connection's liveness clock under the OLD one's timestamp key.
            if (connectedServers[server] === serverWebSocket) {
                lastPongAtMap[server] = System.currentTimeMillis()
            }
        }

        logger.info("\"${server.customName ?: server.name}\" Minecraft server is connected!")
    }

    fun onServerDisconnect(server: Server, serverWebSocket: ServerWebSocket) {
        // Only the connection that is still on record for this server ID may tear its own
        // entries down or report itself disconnected. If a newer connection for the same server
        // ID has already replaced it here (the server restarted and reconnected before this
        // now-stale socket's close made it all the way through - whether that's
        // ServerConnectAPI.onConnectionClosed's DB round trips or the eager call from the
        // heartbeat sweep below), removing unconditionally would strip the secret key and
        // liveness timestamp out from under the LIVE connection, and this log would falsely claim
        // a still-connected server has disconnected. ConcurrentHashMap.remove(key, value) makes
        // the check-and-remove atomic so a racing onServerConnect can never be observed halfway
        // through.
        if (!connectedServers.remove(server, serverWebSocket)) {
            return
        }

        serverSecretKeyMap.remove(server)
        lastPongAtMap.remove(server)

        logger.warn("\"${server.customName ?: server.name}\" Minecraft server is disconnected!")
    }

    /**
     * Application-level WebSocket heartbeat for every connected Minecraft server. Keeps the
     * long-lived /api/server/connection socket non-idle end to end so a reverse proxy in front of
     * Pano (Nginx, Cloudflare, a cloud load balancer) doesn't kill it as idle, and lets this side
     * notice a dead peer quickly instead of relying on TCP-level failure detection. This is purely
     * protocol-level (WebSocket ping/pong frames) and never touches the encrypted message layer.
     *
     * One shared periodic timer sweeps every entry in connectedServers rather than one timer per
     * connection: simpler lifecycle (nothing to cancel/reschedule as servers connect and
     * disconnect) and connectedServers is a ConcurrentHashMap, so the sweep can safely run
     * concurrently with connects/disconnects.
     */
    // Reads mc-server-connection's heartbeat-interval-seconds/heartbeat-timeout-seconds and
    // validates them, mirroring the client-side PlatformManager.resolveHeartbeatSettings: an
    // interval <= 0 would make vertx.setPeriodic throw IllegalArgumentException right out of
    // init() (Main.initServerManager() calls it with no try/catch, so that takes down the whole
    // boot), an interval accepted with no upper bound would defeat the whole point of the
    // heartbeat (the reverse proxy in front of Pano kills the connection as idle long before a
    // ping ever fires), and a timeout that isn't comfortably larger than the interval would start
    // closing perfectly healthy connections after little more than one missed pong. Falls back to
    // PanoConfig's shared defaults with a warning rather than any of that.
    private fun resolveHeartbeatSettings(): HeartbeatSettings {
        // mcServerConnection can be null at runtime despite its non-null Kotlin type - see the
        // field's own doc in PanoConfig - so this falls back to the shipped defaults rather than
        // reading through it directly.
        val config = configManager.config.mcServerConnection
            ?: PanoConfig.Companion.McServerConnectionConfig()
        val configuredInterval = config.heartbeatIntervalSeconds
        val configuredTimeout = config.heartbeatTimeoutSeconds

        if (configuredInterval <= 0 ||
            configuredInterval > MAX_HEARTBEAT_INTERVAL_SECONDS ||
            configuredTimeout < configuredInterval * 2
        ) {
            logger.warn(
                "Invalid mc-server-connection.heartbeat-interval-seconds/heartbeat-timeout-seconds " +
                    "in config.conf (interval must be greater than 0 and at most " +
                    "$MAX_HEARTBEAT_INTERVAL_SECONDS, and timeout must be at least twice the " +
                    "interval). Falling back to the defaults: " +
                    "${PanoConfig.Companion.McServerConnectionConfig.DEFAULT_HEARTBEAT_INTERVAL_SECONDS}s / " +
                    "${PanoConfig.Companion.McServerConnectionConfig.DEFAULT_HEARTBEAT_TIMEOUT_SECONDS}s."
            )

            return HeartbeatSettings(
                PanoConfig.Companion.McServerConnectionConfig.DEFAULT_HEARTBEAT_INTERVAL_SECONDS * 1000L,
                PanoConfig.Companion.McServerConnectionConfig.DEFAULT_HEARTBEAT_TIMEOUT_SECONDS * 1000L
            )
        }

        return HeartbeatSettings(configuredInterval * 1000L, configuredTimeout * 1000L)
    }

    private fun startHeartbeatSweep() {
        val settings = resolveHeartbeatSettings()

        vertx.setPeriodic(settings.intervalMs) {
            val now = System.currentTimeMillis()

            connectedServers.forEach { (server, serverWebSocket) ->
                val lastPongAt = lastPongAtMap[server] ?: now

                if (now - lastPongAt >= settings.timeoutMs) {
                    logger.warn(
                        "\"${server.customName ?: server.name}\" Minecraft server missed heartbeat " +
                            "(no pong for ${(now - lastPongAt) / 1000}s), closing connection."
                    )

                    // Clear this connection's map entries here rather than leaving it entirely to
                    // ServerConnectAPI.onConnectionClosed's closeHandler: that path does three DB
                    // round trips before it ever calls onServerDisconnect, so if the DB is
                    // restarting or the pool is exhausted and one of them throws, these entries
                    // would otherwise be pinned forever - every subsequent sweep would find the
                    // same stale entry, log this same warning and call close() on an
                    // already-dead socket, once per interval, for the life of the process.
                    // onServerDisconnect is a synchronous, DB-free map operation guarded by the
                    // same socket-identity check as onServerConnect's pongHandler, so the (still
                    // expected) later call from onConnectionClosed once its DB work finishes is a
                    // harmless no-op.
                    onServerDisconnect(server, serverWebSocket)

                    serverWebSocket.close()
                } else {
                    try {
                        serverWebSocket.writePing(Buffer.buffer()).onFailure { cause ->
                            logger.warn(
                                "Failed to send heartbeat ping to \"${server.customName ?: server.name}\" " +
                                    "Minecraft server: ${cause.message}"
                            )
                        }
                    } catch (e: Exception) {
                        logger.warn(
                            "Failed to send heartbeat ping to \"${server.customName ?: server.name}\" " +
                                "Minecraft server: ${e.message}"
                        )
                    }
                }
            }
        }
    }

    suspend fun onServerWrite(encryptedText: String, server: Server) {
        val text = Aes256GcmUtil.decrypt(encryptedText, serverSecretKeyMap[server]!!)

        val body = JsonObject(text)
        val event = body.getString("event")

        val eventListener = eventListeners.find { it.getEventName() == event } ?: return

        val requestObj = Gson().fromJson(text, eventListener.requestClass) as ServerEventRequest

        @Suppress("UNCHECKED_CAST")
        val typedListener = eventListener as ServerEvent<ServerEventRequest, ServerEventResponse>

        val message = typedListener.handle(requestObj, server) ?: return

        message.eventId = requestObj.eventId

        sendMessage(message, server)
    }

    fun sendMessage(platformMessage: PlatformMessage, server: Server) {
        val message = platformMessage.encode()
        val encryptedMessage = Aes256GcmUtil.encrypt(message, serverSecretKeyMap[server]!!)

        getConnectedServers()[server]!!.writeTextMessage(encryptedMessage)
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

    companion object {
        // Upper bound for heartbeat-interval-seconds accepted from config.conf: the whole point
        // of this heartbeat is to keep the connection busy enough that a reverse proxy in front
        // of Pano (nginx's default proxy_read_timeout is 60s) never sees it go idle, so an
        // interval anywhere near or past that defeats the feature just as surely as one that's
        // <= 0. Treated as a nonsense value the same as those, rather than accepted silently.
        private const val MAX_HEARTBEAT_INTERVAL_SECONDS = 55
    }
}