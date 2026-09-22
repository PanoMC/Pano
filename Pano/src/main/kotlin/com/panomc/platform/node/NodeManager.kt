package com.panomc.platform.node

import com.google.gson.Gson
import com.panomc.platform.annotation.Event
import com.panomc.platform.config.ConfigManager
import com.panomc.platform.config.PanoConfig
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.Node
import com.panomc.platform.db.model.Server
import com.panomc.platform.error.NodeOffline
import com.panomc.platform.node.dto.NodeMetricSample
import com.panomc.platform.server.alert.AlertManager
import com.panomc.platform.util.Aes256GcmUtil
import io.vertx.core.Vertx
import io.vertx.core.buffer.Buffer
import io.vertx.core.http.ServerWebSocket
import io.vertx.core.json.JsonObject
import io.vertx.kotlin.coroutines.dispatcher
import io.vertx.sqlclient.SqlClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.Logger
import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.context.annotation.Lazy
import org.springframework.context.annotation.Scope
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.SecretKey

// Resolved heartbeat cadence in milliseconds, exactly as ServerManager resolves it. Nodes reuse
// the mc-server-connection block rather than getting a config key of their own: it is the same
// long-lived WebSocket through the same reverse proxies, so the same numbers apply.
private data class NodeHeartbeatSettings(val intervalMs: Long, val timeoutMs: Long)

/**
 * Live registry of the node daemons connected to this Pano.
 *
 * The node counterpart of `ServerManager`, and deliberately its twin: same AES-GCM framing, same
 * `@Event`-bean dispatch, same application-level heartbeat. It is a separate class rather than a
 * generalisation of that one because the two protocols are separate — a node speaks about
 * processes and tasks, a plugin speaks about a running game — and letting a message cross from one
 * socket kind to the other would be a security hole, not a convenience.
 */
@Lazy
@Component
@Scope(value = ConfigurableBeanFactory.SCOPE_SINGLETON)
class NodeManager(
    private val logger: Logger,
    private val databaseManager: DatabaseManager,
    private val applicationContext: AnnotationConfigApplicationContext,
    private val vertx: Vertx,
    private val configManager: ConfigManager
) {
    // Resolved lazily to break a constructor cycle, not as an optimisation: PanelRealtimeHub needs
    // this class, NotificationManager needs the hub, and AlertManager needs the notifications, so
    // resolving it eagerly would leave Spring with a ring of four beans and no way in. A Spring
    // @Lazy proxy is not an option: Kotlin classes are final and CGLIB cannot subclass them.
    private val alertManager: AlertManager by lazy { applicationContext.getBean(AlertManager::class.java) }

    // Lazy for the same reason: the task service reaches the panel hub, and the hub reaches back
    // here.
    private val serverTaskService: ServerTaskService by lazy {
        applicationContext.getBean(ServerTaskService::class.java)
    }

    // Lazy for the same reason: it pushes through the panel hub.
    private val nodeUpdateProgressService: NodeUpdateProgressService by lazy {
        applicationContext.getBean(NodeUpdateProgressService::class.java)
    }

    /** The Pano Agents among the nodes, seeded here at boot; see [AgentNodeDirectory]. */
    private val agentNodeDirectory: AgentNodeDirectory by lazy {
        applicationContext.getBean(AgentNodeDirectory::class.java)
    }

    // ConcurrentHashMap for the same reason as ServerManager's maps: connects and disconnects are
    // resumed on whichever event-loop thread owns that socket while the shared heartbeat sweep
    // iterates the registry on a timer.
    internal val connectedNodes = ConcurrentHashMap<Node, ServerWebSocket>()
    private val nodeSecretKeyMap = ConcurrentHashMap<Node, SecretKey>()
    private val lastPongAtMap = ConcurrentHashMap<Node, Long>()

    // Newest host metrics per node id. Dropped on disconnect, so nothing shows a CPU reading for a
    // machine Pano cannot currently see.
    private val latestMetrics = ConcurrentHashMap<Long, NodeMetricSample>()

    // SHA-256 of the jar each connected node is running, from its hello. In memory rather than on
    // the row because it is only ever true of a live connection: a node reconnects within seconds
    // of Pano restarting and says it again, and a checksum kept for a node that is not there would
    // be a claim about a daemon that may since have been replaced by hand.
    private val jarChecksums = ConcurrentHashMap<Long, String>()

    // The port range each connected node announced in its hello, which new servers on it are
    // allocated inside. In memory for the same reason as the checksum: it is the running daemon's
    // configuration (a flag or an environment variable can change it on the next start), it is
    // said again on every connect, and a server is only ever created on a node that is connected.
    private val portRanges = ConcurrentHashMap<Long, IntRange>()

    /** Requests waiting for a `FILE_RESULT`, keyed by the `eventId` they went out with. */
    private val requestRegistry = NodeRequestRegistry()

    private val heartbeatSweepStarted = AtomicBoolean(false)

    /**
     * Nodes being deleted right now (SM-64). A node that uninstalls itself closes its socket as its
     * last act, and that disconnect is the deletion working, not an outage worth an alert.
     */
    private val removingNodes = ConcurrentHashMap.newKeySet<Long>()

    /** Marks [nodeId] as being deleted, or no longer, for [raiseOfflineAlert]. */
    fun setRemoving(nodeId: Long, removing: Boolean) {
        if (removing) removingNodes.add(nodeId) else removingNodes.remove(nodeId)
    }

    private val eventListeners by lazy {
        val beans = applicationContext.getBeansWithAnnotation(Event::class.java)

        beans.filter { it.value is NodeEvent<*, *> }.map { it.value as NodeEvent<*, *> }.toMutableList()
    }

    fun registerEvent(event: NodeEvent<*, *>) {
        eventListeners.add(event)
    }

    fun unregisterEvent(event: NodeEvent<*, *>) {
        eventListeners.remove(event)
    }

    /**
     * Forces every node offline and arms the heartbeat sweep.
     *
     * A status left over from before this process started describes a socket this process never
     * had, so it is cleared before any node has a chance to reconnect — the same reasoning that
     * makes `ServerManager.init` mark every server offline.
     */
    suspend fun init() {
        val sqlClient = databaseManager.getSqlClient()

        databaseManager.nodeDao.updateAllForOffline(sqlClient)

        // Before any node can connect: a server whose agent is still offline must already say so.
        agentNodeDirectory.seed(databaseManager.nodeDao.getAll(sqlClient))

        startHeartbeatSweep()

        // Armed here because a task outlives the process that created one: after a restart the
        // rows a node was working on are still PENDING or RUNNING, and if that node never comes
        // back nothing else would ever close them.
        serverTaskService.startTimeoutSweep()
    }

    fun onNodeConnect(node: Node, webSocket: ServerWebSocket) {
        // Covers the fresh-install case where init() never ran, exactly as ServerManager does.
        startHeartbeatSweep()
        serverTaskService.startTimeoutSweep()

        connectedNodes[node] = webSocket
        nodeSecretKeyMap[node] = Aes256GcmUtil.base64ToSecretKey(node.aesKey)

        lastPongAtMap[node] = System.currentTimeMillis()

        webSocket.pongHandler {
            // Socket-identity guard: a pong belonging to a superseded connection must not refresh
            // the live one's liveness clock.
            if (connectedNodes[node] === webSocket) {
                lastPongAtMap[node] = System.currentTimeMillis()
            }
        }

        logger.info("\"${node.name}\" node is connected!")
    }

    fun onNodeDisconnect(node: Node, webSocket: ServerWebSocket) {
        // remove(key, value) makes check-and-remove atomic, so a reconnect that already replaced
        // this entry cannot have its secret key and liveness stripped by the old socket's close.
        if (!connectedNodes.remove(node, webSocket)) {
            return
        }

        nodeSecretKeyMap.remove(node)
        lastPongAtMap.remove(node)
        latestMetrics.remove(node.id)
        jarChecksums.remove(node.id)
        portRanges.remove(node.id)

        // A daemon that goes away while updating itself is restarting into the new jar (SM-77).
        // Only here, past the check above: a superseded socket closing late is not a restart.
        try {
            nodeUpdateProgressService.onDisconnect(node.id)
        } catch (e: Exception) {
            logger.warn("Could not record the restart of node ${node.id}'s update: ${e.message}")
        }

        // Anything still waiting on this socket is never going to be answered on it; failing the
        // requests now is the difference between a panel saying "the node went away" and one that
        // spins until every timeout expires.
        requestRegistry.failAll(node.id, NodeOffline())

        logger.warn("\"${node.name}\" node is disconnected!")

        // Raised for every way a node can go away, not only a missed heartbeat: a daemon that was
        // killed closes its socket cleanly, and that is the most common outage of the two. The
        // alert's own cooldown is what keeps a flapping node from saying this every minute.
        raiseOfflineAlert(node)
    }

    private fun resolveHeartbeatSettings(): NodeHeartbeatSettings {
        val config = configManager.config.mcServerConnection
            ?: PanoConfig.Companion.McServerConnectionConfig()
        val configuredInterval = config.heartbeatIntervalSeconds
        val configuredTimeout = config.heartbeatTimeoutSeconds

        if (configuredInterval <= 0 ||
            configuredInterval > MAX_HEARTBEAT_INTERVAL_SECONDS ||
            configuredTimeout < configuredInterval * 2
        ) {
            return NodeHeartbeatSettings(
                PanoConfig.Companion.McServerConnectionConfig.DEFAULT_HEARTBEAT_INTERVAL_SECONDS * 1000L,
                PanoConfig.Companion.McServerConnectionConfig.DEFAULT_HEARTBEAT_TIMEOUT_SECONDS * 1000L
            )
        }

        return NodeHeartbeatSettings(configuredInterval * 1000L, configuredTimeout * 1000L)
    }

    private fun startHeartbeatSweep() {
        if (!heartbeatSweepStarted.compareAndSet(false, true)) {
            return
        }

        val settings = resolveHeartbeatSettings()

        vertx.setPeriodic(settings.intervalMs) {
            val now = System.currentTimeMillis()

            connectedNodes.forEach { (node, webSocket) ->
                val lastPongAt = lastPongAtMap[node] ?: now

                if (now - lastPongAt >= settings.timeoutMs) {
                    logger.warn(
                        "\"${node.name}\" node missed heartbeat (no pong for ${(now - lastPongAt) / 1000}s), " +
                            "closing connection."
                    )

                    // Torn down here and not only in the close handler: that path does database
                    // work first, and if it throws the entry would be pinned forever and every
                    // later sweep would log this again for a dead socket.
                    onNodeDisconnect(node, webSocket)

                    webSocket.close()
                } else {
                    try {
                        webSocket.writePing(Buffer.buffer()).onFailure { cause ->
                            logger.warn("Failed to send heartbeat ping to \"${node.name}\" node: ${cause.message}")
                        }
                    } catch (e: Exception) {
                        logger.warn("Failed to send heartbeat ping to \"${node.name}\" node: ${e.message}")
                    }
                }
            }
        }
    }

    /**
     * Handles one frame from [node].
     *
     * Ordering is FIFO only up to this call: `NodeConnectAPI` reads the socket in order and
     * launches a coroutine per frame, so handlers start in the order the node sent them and then
     * run concurrently from their first suspension point on. Anything whose frames have to be
     * applied in order — task progress above all — serialises itself, it cannot rely on getting
     * here alone.
     */
    suspend fun onNodeWrite(encryptedText: String, node: Node) {
        val text = Aes256GcmUtil.decrypt(encryptedText, nodeSecretKeyMap[node]!!)

        val body = JsonObject(text)
        val event = body.getString("event")

        // Replies are not events: they belong to a coroutine that is already waiting, not to an
        // `@Event` bean, so they are taken off the wire before the listener lookup.
        if (event == REPLY_EVENT) {
            requestRegistry.complete(node.id, body.getString("eventId"), body)

            return
        }

        val eventListener = eventListeners.find { it.getEventName() == event } ?: return

        val requestObj = Gson().fromJson(text, eventListener.requestClass) as NodeEventRequest

        @Suppress("UNCHECKED_CAST")
        val typedListener = eventListener as NodeEvent<NodeEventRequest, NodeEventResponse>

        val message = typedListener.handle(requestObj, node) ?: return

        message.eventId = requestObj.eventId

        sendMessage(message, node)
    }

    fun sendMessage(nodeMessage: NodeMessage, node: Node) {
        val message = nodeMessage.encode()
        val encryptedMessage = Aes256GcmUtil.encrypt(message, nodeSecretKeyMap[node]!!)

        connectedNodes[node]!!.writeTextMessage(encryptedMessage)
    }

    /**
     * Sends [nodeMessage] to the node with [nodeId] and reports whether it went out.
     *
     * Never throws when the node is gone: a socket can close between a caller's connection check
     * and this call, and every push is best effort.
     */
    fun sendMessage(nodeId: Long, nodeMessage: NodeMessage): Boolean {
        val node = getConnectedNodeById(nodeId) ?: return false

        return try {
            sendMessage(nodeMessage, node)

            true
        } catch (e: Exception) {
            logger.warn("Failed to send ${nodeMessage.getResponseName()} to node $nodeId: ${e.message}")

            false
        }
    }

    /**
     * Sends [nodeMessage] to [nodeId] and suspends until the node answers.
     *
     * The answer is the `FILE_RESULT` payload verbatim, which always carries `ok` and, when that
     * is false, an `error` the caller maps onto an HTTP error. The timeout exists because a node
     * is a separate process on someone else's machine: it can be swapping, its disk can be
     * wedged, and a panel request must not hold a connection open waiting for it to come back.
     *
     * Throws [NodeOffline] when the node is not connected, when the frame could not be written,
     * and when the answer did not arrive in time — all three are the same thing to the panel,
     * which is "try again".
     */
    suspend fun request(
        nodeId: Long,
        nodeMessage: NodeRequestMessage,
        timeoutMs: Long = DEFAULT_REQUEST_TIMEOUT_MS
    ): JsonObject {
        val node = getConnectedNodeById(nodeId) ?: throw NodeOffline()

        val (eventId, result) = requestRegistry.register(nodeId)

        nodeMessage.eventId = eventId

        try {
            sendMessage(nodeMessage, node)
        } catch (e: Exception) {
            requestRegistry.forget(eventId)

            logger.warn("Failed to send ${nodeMessage.getResponseName()} to node $nodeId: ${e.message}")

            throw NodeOffline()
        }

        val payload = withTimeoutOrNull(timeoutMs) { result.await() }

        if (payload == null) {
            requestRegistry.forget(eventId)

            logger.warn("Node $nodeId did not answer ${nodeMessage.getResponseName()} in ${timeoutMs}ms.")

            throw NodeOffline()
        }

        return payload
    }

    /** The live [Node] instance for [id], or null when nothing is connected under that id. */
    fun getConnectedNodeById(id: Long): Node? = connectedNodes.keys.find { it.id == id }

    fun isConnected(id: Long) = getConnectedNodeById(id) != null

    fun getConnectedNodes() = connectedNodes.toMap()

    fun setLatestMetrics(nodeId: Long, sample: NodeMetricSample) {
        latestMetrics[nodeId] = sample
    }

    fun getLatestMetrics(nodeId: Long): NodeMetricSample? = latestMetrics[nodeId]

    /** Records the checksum of the daemon jar [nodeId] reported in its hello. */
    fun setJarSha256(nodeId: Long, sha256: String?) {
        val value = sha256?.trim()?.takeIf { it.isNotEmpty() }

        if (value == null) {
            jarChecksums.remove(nodeId)

            return
        }

        jarChecksums[nodeId] = value
    }

    /** What [nodeId]'s daemon jar hashes to, or null when it is offline or too old to say. */
    fun getJarSha256(nodeId: Long): String? = jarChecksums[nodeId]

    /** Records the port range [nodeId] reported in its hello; null forgets it. */
    fun setPortRange(nodeId: Long, range: IntRange?) {
        if (range == null) {
            portRanges.remove(nodeId)

            return
        }

        portRanges[nodeId] = range
    }

    /**
     * The ports [nodeId]'s servers may bind, or null when it is offline or too old to say -- and
     * then [ServerPortAllocator] allocates the way it always has.
     */
    fun getPortRange(nodeId: Long): IntRange? = portRanges[nodeId]

    /** Drops every in-memory trace of a node that no longer exists. */
    fun onNodeDeleted(nodeId: Long) {
        latestMetrics.remove(nodeId)
        jarChecksums.remove(nodeId)
        portRanges.remove(nodeId)
        agentNodeDirectory.remove(nodeId)
        nodeUpdateProgressService.forget(nodeId)

        alertManager.onNodeDeleted(nodeId)
    }

    /**
     * Reports a node going away, off the path that noticed it.
     *
     * A disconnect is handled on the event loop that owned the socket and must not wait on a
     * database round trip, and an alert that throws must not be able to leave the registry with a
     * node in it that is not there.
     */
    private fun raiseOfflineAlert(node: Node) {
        CoroutineScope(vertx.dispatcher()).launch {
            try {
                val sqlClient = databaseManager.getSqlClient()

                // A node that is being deleted, or already was, went away on purpose.
                if (node.id in removingNodes || databaseManager.nodeDao.getById(node.id, sqlClient) == null) {
                    return@launch
                }

                // So did one restarting onto an update Pano sent it: it is back in seconds, and the
                // header and the nodes page already say "Restarting…".
                if (nodeUpdateProgressService.isUpdating(node.id)) {
                    return@launch
                }

                alertManager.onNodeOffline(node, sqlClient)
            } catch (e: Exception) {
                logger.warn("Could not raise the offline alert for node ${node.id}: ${e.message}")
            }
        }
    }

    fun closeConnection(id: Long) {
        connectedNodes
            .filter { it.key.id == id }
            .forEach { it.value.close() }
    }

    /**
     * Resolves the server a node is talking about.
     *
     * Node traffic names servers by uuid and never by database id, and the row is only handed back
     * when it actually belongs to [node]. This is the single choke point for that rule: a node
     * that guesses another node's server uuid, or a linked server's, gets nothing.
     */
    suspend fun resolveServer(node: Node, serverUuid: String?, sqlClient: SqlClient): Server? {
        if (serverUuid.isNullOrBlank()) {
            return null
        }

        val server = databaseManager.serverDao.getByUuid(serverUuid, sqlClient) ?: return null

        if (server.nodeId != node.id) {
            logger.warn("Node ${node.id} referenced server uuid it does not own, ignoring.")

            return null
        }

        return server
    }

    companion object {
        // Same ceiling as ServerManager: an interval at or beyond a typical reverse-proxy idle
        // timeout defeats the point of having a heartbeat at all.
        private const val MAX_HEARTBEAT_INTERVAL_SECONDS = 55

        /** The one name every reply arrives under; the `eventId` says which request it answers. */
        const val REPLY_EVENT = "FILE_RESULT"

        /** How long a file operation may take before the panel is told to try again. */
        const val DEFAULT_REQUEST_TIMEOUT_MS = 30_000L

        /** Transfers move whole files, so they get the ticket's lifetime rather than 30 seconds. */
        const val TRANSFER_REQUEST_TIMEOUT_MS = 10 * 60 * 1000L
    }
}
