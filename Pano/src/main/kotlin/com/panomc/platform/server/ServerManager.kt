package com.panomc.platform.server

import com.google.gson.Gson
import com.panomc.platform.annotation.Event
import com.panomc.platform.config.ConfigManager
import com.panomc.platform.config.PanoConfig
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.Server
import com.panomc.platform.error.ServerOffline
import com.panomc.platform.server.console.ServerConsoleBuffer
import com.panomc.platform.server.dto.ServerMetricSample
import com.panomc.platform.server.dto.ServerPluginData
import com.panomc.platform.util.Aes256GcmUtil
import io.vertx.core.Vertx
import io.vertx.core.buffer.Buffer
import io.vertx.core.http.ServerWebSocket
import io.vertx.core.json.JsonObject
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.Logger
import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.context.annotation.Lazy
import org.springframework.context.annotation.Scope
import org.springframework.stereotype.Component
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
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

    // Console history per server id, not per Server instance: the buffer has to survive the
    // server reconnecting (and therefore a brand new Server row being loaded), because the lines
    // leading up to a restart are exactly the ones an admin opens the console page to read. The
    // ring cap inside ServerConsoleBuffer bounds what that costs; entries are only removed when
    // the server itself is deleted from Pano.
    private val consoleBuffers = ConcurrentHashMap<Long, ServerConsoleBuffer>()

    // Newest performance sample per server id. Exactly one is kept: the minute-resolution history
    // lives in the server_metric table, this is only what "right now" means for the panel and for
    // the roster's ping column. Dropped on disconnect so an offline server never shows a TPS
    // figure from before it went down.
    private val latestMetrics = ConcurrentHashMap<Long, ServerMetricSample>()

    // Installed plugin list per server id. In memory only and dropped on disconnect: it describes
    // what is on that server's disk right now, which nothing on this side can vouch for once the
    // server is gone.
    private val installedPlugins = ConcurrentHashMap<Long, List<ServerPluginData>>()

    // Requests waiting for a plugin to answer, keyed by the eventId they went out with.
    private val requestRegistry = ServerRequestRegistry()

    // Guards startHeartbeatSweep so the periodic timer is armed exactly once no matter which of
    // its two call sites gets there first. init() calls it unconditionally on boot, but init() is
    // itself only reached via Main.initServerManager() when the platform is already installed
    // (Main.init() checks SetupManager.isSetupDone() first) - on a freshly installed Pano, the
    // setup wizard's FinishAPI never calls serverManager.init(), so without this the sweep would
    // never start until the process is restarted, and a Minecraft server that connects in that
    // window would get no pings and never be timed out if it died. onServerConnect() is the actual
    // fix: it also calls startHeartbeatSweep(), so the very first connection - fresh install or
    // not - arms the timer regardless of whether init() ever ran. Plain HTTP connection handling
    // can dispatch concurrent onServerConnect calls onto different event-loop threads (a Vert.x
    // HttpServer bound to one port balances across all of them), and init() could in principle
    // race a very early connection too, so this needs to be safe under concurrent callers, not
    // just idempotent under sequential ones - hence compareAndSet rather than a plain boolean
    // check-then-set.
    private val heartbeatSweepStarted = AtomicBoolean(false)

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
        // See heartbeatSweepStarted's own doc: this is what actually covers the fresh-install
        // gap, since init() alone isn't guaranteed to run before the first connection.
        startHeartbeatSweep()

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
        latestMetrics.remove(server.id)
        installedPlugins.remove(server.id)

        // Anything still waiting on this socket is never going to be answered on it, and a panel
        // request left to sit out its full timeout after a server restart reads as Pano hanging.
        requestRegistry.failAll(server.id, ServerOffline())

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
        // compareAndSet makes "only the first caller wins" atomic across concurrent callers (see
        // heartbeatSweepStarted's doc) - anything after the first returns immediately instead of
        // arming a second competing vertx.setPeriodic timer.
        if (!heartbeatSweepStarted.compareAndSet(false, true)) {
            return
        }

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

        // An agent-lite reply answers nine different requests with nine different bodies, so the
        // whole frame is kept rather than only the fields this Pano version happens to declare.
        // See RawPayloadCarrier for why that is not a shortcut.
        if (requestObj is RawPayloadCarrier) {
            requestObj.raw = body
        }

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

    /**
     * Sends [platformMessage] to the server with [serverId] and reports whether it went out.
     *
     * The id-keyed overload for callers that only hold an id (panel endpoints, the realtime hub).
     * It never throws when the server is gone: a socket can close between the caller's own
     * connection check and this call, and a push is always best effort.
     */
    fun sendMessage(serverId: Long, platformMessage: PlatformMessage): Boolean {
        val server = getConnectedServerById(serverId) ?: return false

        return try {
            sendMessage(platformMessage, server)

            true
        } catch (e: Exception) {
            logger.warn(
                "Failed to send ${platformMessage.getResponseName()} to Minecraft server $serverId: ${e.message}"
            )

            false
        }
    }

    /**
     * Sends [platformMessage] to the server with [serverId] and suspends until the plugin answers.
     *
     * The answer is the decoded result event, which the caller narrows to the reply it asked for.
     * The timeout exists because the other end is a game server: it can be frozen on a chunk
     * load, mid-restart, or simply running a plugin too old to know the message, and a panel
     * request must not hold a connection open waiting for it.
     *
     * Throws [ServerOffline] when the server is not connected, when the frame could not be
     * written, and when the answer did not arrive in time — all three are the same thing to the
     * panel, which is "try again".
     */
    suspend fun request(
        serverId: Long,
        platformMessage: ServerRequestMessage,
        timeoutMs: Long = DEFAULT_REQUEST_TIMEOUT_MS
    ): ServerEventRequest {
        val server = getConnectedServerById(serverId) ?: throw ServerOffline()

        val (eventId, result) = requestRegistry.register(serverId)

        platformMessage.eventId = eventId

        try {
            sendMessage(platformMessage, server)
        } catch (e: Exception) {
            requestRegistry.forget(eventId)

            logger.warn(
                "Failed to send ${platformMessage.getResponseName()} to Minecraft server $serverId: ${e.message}"
            )

            throw ServerOffline()
        }

        val reply = withTimeoutOrNull(timeoutMs) { result.await() }

        if (reply == null) {
            requestRegistry.forget(eventId)

            logger.warn(
                "Minecraft server $serverId did not answer " +
                    "${platformMessage.getResponseName()} in ${timeoutMs}ms."
            )

            throw ServerOffline()
        }

        return reply
    }

    /**
     * Hands a plugin's reply to whoever is waiting for it, and reports whether anyone was.
     *
     * Called by the result events; a reply that matches no outstanding request is dropped, which
     * covers both a late answer and a server quoting an id that was never its own.
     */
    fun completeRequest(serverId: Long, eventId: UUID?, reply: ServerEventRequest): Boolean =
        requestRegistry.complete(serverId, eventId?.toString(), reply)

    /** The live [Server] instance for [id], or null when nothing is connected under that id. */
    fun getConnectedServerById(id: Long): Server? = connectedServers.keys.find { it.id == id }

    /** Stores the newest performance sample reported by [serverId]. */
    fun setLatestMetrics(serverId: Long, sample: ServerMetricSample) {
        latestMetrics[serverId] = sample
    }

    /** Newest performance sample of [serverId], or null when it never sent one since connecting. */
    fun getLatestMetrics(serverId: Long): ServerMetricSample? = latestMetrics[serverId]

    /**
     * Every server Pano currently holds a sample for.
     *
     * Not the same set as [getConnectedServers] any more: a managed server with no plugin in it
     * reports through its node and never appears there, and the per-minute rollup has to record
     * it too or a node-only server's chart is empty forever (§2.4.17 A).
     */
    fun getServerIdsWithMetrics(): Set<Long> = latestMetrics.keys.toSet()

    /** Replaces the known plugin list of [serverId] with what it just reported. */
    fun setInstalledPlugins(serverId: Long, plugins: List<ServerPluginData>) {
        installedPlugins[serverId] = plugins
    }

    /** Plugins [serverId] reported, or null when it never reported a list since connecting. */
    fun getInstalledPlugins(serverId: Long): List<ServerPluginData>? = installedPlugins[serverId]

    /** Console history of [serverId], created on first use. */
    fun getConsoleBuffer(serverId: Long): ServerConsoleBuffer =
        consoleBuffers.computeIfAbsent(serverId) { ServerConsoleBuffer() }

    /**
     * Drops every in-memory trace of a server that no longer exists.
     *
     * Called when a server is removed from the panel or unlinks itself, so the console history
     * cannot outlive the row it belongs to.
     */
    fun onServerDeleted(serverId: Long) {
        consoleBuffers.remove(serverId)
        latestMetrics.remove(serverId)
        installedPlugins.remove(serverId)
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

        // How long a request waits by default. Generous, because a game server answers on its
        // main thread and that thread has a tick to finish first; callers a page load is waiting
        // on pass something far shorter.
        const val DEFAULT_REQUEST_TIMEOUT_MS = 10_000L
    }
}