package com.panomc.platform.panel

import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.PermissionManager
import com.panomc.platform.auth.Permission
import com.panomc.platform.auth.panel.permission.ManageServerConsolePermission
import com.panomc.platform.auth.panel.permission.ManageServerPlayersPermission
import com.panomc.platform.auth.panel.permission.ManageServerPluginsPermission
import com.panomc.platform.auth.panel.permission.ManageServersPermission
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.Server
import com.panomc.platform.db.model.ServerTask
import com.panomc.platform.node.AgentNodeDirectory
import com.panomc.platform.node.NodeInstallScriptProvider
import com.panomc.platform.node.NodeManager
import com.panomc.platform.node.NodeProtocol
import com.panomc.platform.node.NodeUpdateProgressStore
import com.panomc.platform.node.dto.NodeMetricSample
import com.panomc.platform.node.message.SetNodeMetricsIntervalMessage
import com.panomc.platform.node.message.ConsoleStreamMessage as NodeConsoleStreamMessage
import com.panomc.platform.node.message.SetMetricsIntervalMessage as NodeSetMetricsIntervalMessage
import com.panomc.platform.server.ServerActiveTaskStore
import com.panomc.platform.server.ServerActivityNotifier
import com.panomc.platform.server.ServerCapability
import com.panomc.platform.server.ServerManager
import com.panomc.platform.server.dto.ConsoleLineData
import com.panomc.platform.server.dto.ServerMetricSample
import com.panomc.platform.server.dto.ServerPluginData
import com.panomc.platform.server.feature.ServerFeatureResolver
import com.panomc.platform.server.message.ConsoleStreamMessage
import com.panomc.platform.server.message.SetMetricsIntervalMessage as PluginSetMetricsIntervalMessage
import com.panomc.platform.server.metrics.MetricsRates
import com.panomc.platform.server.metrics.ServerLatestMetrics
import com.panomc.platform.server.players.ServerRosterBuilder
import io.vertx.core.Vertx
import io.vertx.core.http.ServerWebSocket
import io.vertx.core.json.JsonArray
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
 * [notifyPanelNotificationRefresh] nudges clients to re-fetch notification state over HTTP
 * (site + panel).
 *
 * It is also the browser end of the console stream: a panel subscribes to one server's console
 * here, and this class is what decides whether the plugin should be streaming at all, so a server
 * nobody is watching never pays for the feature.
 */
@Lazy
@Component
@Scope(value = ConfigurableBeanFactory.SCOPE_SINGLETON)
class PanelRealtimeHub(
    private val databaseManager: DatabaseManager,
    private val vertx: Vertx,
    private val serverManager: ServerManager,
    private val nodeManager: NodeManager,
    private val authProvider: AuthProvider,
    private val permissionManager: PermissionManager,
    private val featureResolver: ServerFeatureResolver,
    private val activeTaskStore: ServerActiveTaskStore,
    private val agentNodeDirectory: AgentNodeDirectory,
    private val nodeUpdateProgressStore: NodeUpdateProgressStore
) {
    private data class ClientSession(
        val userId: Long,
        val canManageServers: Boolean,
        val canManageNodes: Boolean,
        var subscribeNotifications: Boolean = true,
        var subscribeNodes: Boolean = false,
        /**
         * How often this session wants the host metrics of the nodes it watches, in ms (SM-65,
         * §2.4.30); the default unless the panel asked for something else.
         */
        var nodeMetricsIntervalMs: Long = MetricsRates.DEFAULT_INTERVAL_MS,
        /**
         * The nodes this session watches when [subscribeNodes] is on; null is every node (the nodes
         * page), a set is the node detail page's one node.
         */
        var subscribeNodeIds: Set<Long>? = null,
        var subscribeServers: Boolean = false,
        var subscribeServerId: Long? = null,
        var subscribeConsoleServerId: Long? = null,
        var subscribeMetricsServerId: Long? = null,
        /**
         * How often this session wants the metrics of [subscribeMetricsServerId], in ms (§2.4.23 A);
         * the default unless the panel asked for something else.
         */
        var metricsIntervalMs: Long = MetricsRates.DEFAULT_INTERVAL_MS,
        /**
         * Servers watched all at once, e.g. the servers modal's live vitals: each gets the same
         * `metrics` frames as [subscribeMetricsServerId], at the same [metricsIntervalMs].
         */
        var subscribeMetricsServerIds: Set<Long> = emptySet(),
        /** Bumped by every `subscribeMetricsServerIds`, so a slow permission check cannot undo a newer one. */
        var metricsServerIdsGeneration: Long = 0,
        var subscribePlayersServerId: Long? = null,
        var subscribePluginsServerId: Long? = null
    )

    private val sessions = ConcurrentHashMap<ServerWebSocket, ClientSession>()

    // Server ids the plugin has been asked to stream console output for. This is Pano's view of
    // what it told each plugin, not a guess about the plugin's state, so the 0 -> 1 and 1 -> 0
    // transitions below send exactly one message each.
    private val streamingServers = ConcurrentHashMap.newKeySet<Long>()

    // serverId -> vertx timer id of a pending "stop streaming" grace period. Flipping the stream
    // off the instant the last viewer leaves would thrash the plugin every time someone switches
    // panel tabs, so the stop is delayed and cancelled if a viewer comes back.
    private val stopStreamTimers = ConcurrentHashMap<Long, Long>()

    /**
     * What each server has been asked to report its metrics at (§2.4.23 A). The rate a server is
     * asked for is the fastest one any of its metrics subscribers wants; see [reconcileMetricsRates].
     */
    private val metricsRates = MetricsRates()

    /**
     * What each node has been asked to report its host metrics at (SM-65, §2.4.30): the fastest
     * `nodeMetricsIntervalMs` of the sessions watching it; see [reconcileNodeMetricsRates].
     */
    private val nodeMetricsRates = MetricsRates()

    /** Paces `taskProgress` for the server-list audiences; see [pushTaskProgress]. */
    private val taskFrameThrottle = TaskFrameThrottle()

    /** Servers with a `serverActivity` frame already on its way; see [notifyServerActivity]. */
    private val pendingActivity = ConcurrentHashMap.newKeySet<Long>()

    init {
        // Every server-scoped activity entry, from any writer, ends up here (see the notifier).
        ServerActivityNotifier.sink = ::notifyServerActivity

        // The lease: a fast rate is re-sent every minute while somebody still wants it, and the
        // sessions are looked at again on the way, because a dead socket can be dropped from
        // [sessions] by any of the writers below without ever reaching [unregister].
        vertx.setPeriodic(MetricsRates.RENEW_MS) {
            val sentNow = reconcileMetricsRates()

            metricsRates.renewals()
                .filterKeys { it !in sentNow }
                .forEach { (serverId, intervalMs) -> sendMetricsRate(serverId, intervalMs) }

            val nodesSentNow = reconcileNodeMetricsRates()

            nodeMetricsRates.renewals()
                .filterKeys { it !in nodesSentNow }
                .forEach { (nodeId, intervalMs) -> sendNodeMetricsRate(nodeId, intervalMs) }
        }
    }

    fun register(socket: ServerWebSocket, userId: Long, canManageServers: Boolean, canManageNodes: Boolean) {
        sessions[socket] = ClientSession(
            userId = userId,
            canManageServers = canManageServers,
            canManageNodes = canManageNodes
        )
    }

    fun unregister(socket: ServerWebSocket) {
        val session = sessions.remove(socket)
        val consoleServerId = session?.subscribeConsoleServerId

        if (consoleServerId != null) {
            reconcileConsoleStream(consoleServerId)
        }

        // The last fast watcher leaving is what sends a server back to its default rate.
        if (session?.subscribeMetricsServerId != null || session?.subscribeMetricsServerIds?.isNotEmpty() == true) {
            reconcileMetricsRates()
        }

        if (session?.subscribeNodes == true) {
            reconcileNodeMetricsRates()
        }
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
        // Nodes are their own permission: someone who can manage servers has no business watching
        // the machines they run on unless they were also given MANAGE_NODES.
        // The rate and the node set before the subscription itself, for the same reason as the
        // server metrics below: one frame carrying all three applies them together.
        if (body.containsKey("nodeMetricsIntervalMs")) {
            s.nodeMetricsIntervalMs = MetricsRates.clamp(body.getValue("nodeMetricsIntervalMs"))
        }
        if (body.containsKey("subscribeNodeIds")) {
            s.subscribeNodeIds = body.getValue("subscribeNodeIds")?.let { MetricsRates.serverIds(it).toSet() }
        }
        if (body.containsKey("subscribeNodes")) {
            s.subscribeNodes = s.canManageNodes && body.getBoolean("subscribeNodes", false)
        }
        if (body.containsKey("nodeMetricsIntervalMs") || body.containsKey("subscribeNodeIds") ||
            body.containsKey("subscribeNodes")
        ) {
            reconcileNodeMetricsRates()
        }
        // The console has its own permission, which can be granted for a single server, so it is
        // checked per subscription instead of riding on canManageServers.
        if (body.containsKey("subscribeConsoleServerId")) {
            applyConsoleSubscription(socket, s, readServerId(body, "subscribeConsoleServerId"))
        }
        // Metrics and the player roster are separate permissions again, so each is checked on its
        // own instead of one subscription implying the others.
        // Before the subscription, so a frame that carries both applies the new rate to the new
        // server rather than briefly to the old one.
        if (body.containsKey("metricsIntervalMs")) {
            s.metricsIntervalMs = MetricsRates.clamp(body.getValue("metricsIntervalMs"))

            reconcileMetricsRates()
        }
        if (body.containsKey("subscribeMetricsServerId")) {
            applyScopedSubscription(
                socket,
                s,
                readServerId(body, "subscribeMetricsServerId"),
                ManageServersPermission(),
                { s.subscribeMetricsServerId },
                {
                    s.subscribeMetricsServerId = it

                    reconcileMetricsRates()
                }
            )
        }
        if (body.containsKey("subscribeMetricsServerIds")) {
            applyMetricsServerIdsSubscription(socket, s, MetricsRates.serverIds(body.getValue("subscribeMetricsServerIds")))
        }
        if (body.containsKey("subscribePlayersServerId")) {
            applyScopedSubscription(
                socket,
                s,
                readServerId(body, "subscribePlayersServerId"),
                ManageServerPlayersPermission(),
                { s.subscribePlayersServerId },
                { s.subscribePlayersServerId = it }
            )
        }
        if (body.containsKey("subscribePluginsServerId")) {
            applyScopedSubscription(
                socket,
                s,
                readServerId(body, "subscribePluginsServerId"),
                ManageServerPluginsPermission(),
                { s.subscribePluginsServerId },
                { s.subscribePluginsServerId = it }
            )
        }
    }

    private fun readServerId(body: JsonObject, key: String): Long? {
        val value = body.getValue(key)

        return if (value is Number) value.toLong() else null
    }

    /**
     * Tells the user's open panel session(s) to re-fetch notification state over HTTP.
     */
    fun notifyPanelNotificationRefresh(userId: Long) {
        val text = JsonObject()
            .put("type", "notificationRefresh")
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
                .put("server", featureResolver.toPublicJsonObject(server))
                .encode()
            writeToRelevantSubscribers(text, serverId)
        }
    }

    /**
     * Re-pushes every server of one node, because its `features` just changed (SM-52).
     *
     * A node connecting or dropping silently changes what half the panel's controls can do, and
     * the row itself does not change at all -- nothing is written to the database when a socket
     * opens -- so without this a panel left open would keep showing power buttons for a daemon
     * that is gone until somebody reloaded the page.
     */
    fun notifyNodeServersUpdated(nodeId: Long) {
        CoroutineScope(vertx.dispatcher()).launch {
            val sqlClient = databaseManager.getSqlClient()

            databaseManager.serverDao.getAllByNodeId(nodeId, sqlClient).forEach { server ->
                notifyServerUpdated(server.id)
            }
        }
    }

    fun notifyServerRemoved(serverId: Long) {
        val text = JsonObject()
            .put("type", "serverRemoved")
            .put("serverId", serverId)
            .encode()
        writeToRelevantSubscribers(text, serverId)
    }

    /**
     * Re-arms console streaming for a server that just (re)connected.
     *
     * A plugin always comes up with its stream disabled, so a panel that sat on the console page
     * through a server restart would go silent forever unless Pano asks again on its behalf.
     */
    fun onServerConnected(serverId: Long) {
        cancelStopStreamTimer(serverId)

        if (consoleSubscriberCount(serverId) == 0) {
            streamingServers.remove(serverId)

            return
        }

        streamingServers.add(serverId)

        requestConsoleStream(serverId, true)

        broadcastConsoleState(serverId)
    }

    /** Lets console viewers know the stream went quiet because the server dropped off. */
    fun onServerDisconnected(serverId: Long) {
        if (consoleSubscriberCount(serverId) == 0) {
            return
        }

        broadcastConsoleState(serverId)
    }

    /** Whether Pano currently has console streaming switched on for [serverId]. */
    fun isConsoleStreaming(serverId: Long) = streamingServers.contains(serverId)

    /**
     * Fans a batch of console lines out to everyone watching [serverId].
     *
     * [dropped] is what the sender reported for this batch, so the panel can render a marker
     * exactly where the gap happened.
     */
    fun pushConsoleLines(serverId: Long, lines: List<ConsoleLineData>, dropped: Long) {
        if (lines.isEmpty() && dropped <= 0) {
            return
        }

        val payload = JsonArray()

        // Colour spans ride along as `c` when a line has them (§2.4.21 D); already validated.
        lines.forEach { line -> payload.add(line.toJsonObject()) }

        val text = JsonObject()
            .put("type", "console")
            .put("serverId", serverId)
            .put("lines", payload)
            .put("dropped", dropped)
            .encode()

        writeToConsoleSubscribers(text, serverId)
    }

    /**
     * Fans a performance sample out to everyone watching [serverId]'s metrics.
     *
     * [nodeId] is the server's node, or null for a linked server: it is where `hostMemTotal` comes
     * from (§2.4.18), the memory a node-sourced RAM figure is read against.
     */
    fun pushMetrics(serverId: Long, sample: ServerMetricSample, nodeId: Long?) {
        // The same `latest` shape the metrics endpoints return, `hostMemTotal` included, so a
        // node-sourced RAM figure can be read against the host's memory on every live frame too.
        val latest = ServerLatestMetrics.latestJson(
            sample,
            ServerLatestMetrics.hostMemTotal(nodeId?.let { nodeManager.getLatestMetrics(it) })
        )

        val text = JsonObject()
            .put("type", "metrics")
            .put("serverId", serverId)
            .put("sample", latest)
            .encode()

        writeToSubscribers(text) { it.subscribeMetricsServerId == serverId || serverId in it.subscribeMetricsServerIds }
    }

    /**
     * Rebuilds and pushes the online roster of [serverId].
     *
     * Called from the join and quit events, which are the only moments the roster actually
     * changes; the ten-second metrics sample is not used for this, so the list never lags behind
     * the event that changed it.
     */
    fun notifyServerPlayersUpdated(serverId: Long) {
        if (!hasSubscriber { it.subscribePlayersServerId == serverId }) {
            return
        }

        CoroutineScope(vertx.dispatcher()).launch {
            val sqlClient = databaseManager.getSqlClient()
            val players = databaseManager.serverPlayerDao.getAllByServerId(serverId, sqlClient)
            val roster = ServerRosterBuilder.build(
                players,
                serverManager.getLatestMetrics(serverId),
                ServerRosterBuilder.panoUsernames(players.map { it.username }, databaseManager.userDao, sqlClient)
            )

            val text = JsonObject()
                .put("type", "players")
                .put("serverId", serverId)
                .put("players", JsonArray(roster))
                .encode()

            writeToSubscribers(text) { it.subscribePlayersServerId == serverId }
        }
    }

    /**
     * Notifies node watchers that a node row changed. Loads the latest row from the database.
     *
     * Unapproved nodes are pushed too, unlike servers: the nodes page is where an admin accepts a
     * pairing request, so it has to see the node that is waiting.
     */
    fun notifyNodeUpdated(nodeId: Long) {
        // A Pano Agent's changes go to its server's watchers, who are not node subscribers.
        if (!hasSubscriber { it.subscribeNodes } && !agentNodeDirectory.isAgent(nodeId)) {
            return
        }

        CoroutineScope(vertx.dispatcher()).launch {
            val sqlClient = databaseManager.getSqlClient()
            val node = databaseManager.nodeDao.getById(nodeId, sqlClient) ?: return@launch

            // A Pano Agent is shown as its server, never on the nodes page: what changed about it
            // (online, version) reaches the panel as that server's `server` frame instead.
            if (node.agent) {
                databaseManager.serverDao.getAllByNodeId(nodeId, sqlClient).forEach { notifyServerUpdated(it.id) }

                return@launch
            }

            val payload = node.toPublicJsonObject()
                .put("metrics", nodeManager.getLatestMetrics(nodeId)?.toJsonObject())
                .put("connected", nodeManager.isConnected(nodeId))
                // `{ version, status, percent, message }` while its daemon updates, and the version
                // an update installs, as on `GET /api/panel/nodes` (SM-77).
                .put("updateProgress", nodeUpdateProgressStore.get(nodeId)?.toNodeJsonObject())
                .put("latestVersion", NodeInstallScriptProvider.releaseVersion())

            val text = JsonObject()
                .put("type", "node")
                .put("node", payload)
                .encode()

            writeToSubscribers(text) { it.subscribeNodes }
        }
    }

    /**
     * Pushes one `NODE_METRICS` sample to the sessions watching [nodeId] (SM-65, §2.4.30).
     *
     * A light frame of its own rather than the full `node` frame: at a half-second rate re-reading
     * the row for every sample would be a database query per node per watcher twice a second, for
     * a row that has not changed. The `node` frame stays what state changes send.
     */
    fun pushNodeMetrics(nodeId: Long, sample: NodeMetricSample) {
        // Not a node anybody is watching on the nodes page (see notifyNodeUpdated).
        if (agentNodeDirectory.isAgent(nodeId)) {
            return
        }

        val text = JsonObject()
            .put("type", "nodeMetrics")
            .put("nodeId", nodeId)
            .put("metrics", sample.toJsonObject())
            .encode()

        writeToSubscribers(text) { it.watchesNode(nodeId) }
    }

    /**
     * A node just said hello: whatever rate it was told before went with its previous connection,
     * so it is told again if anybody is watching it fast.
     */
    fun onNodeConnected(nodeId: Long) {
        nodeMetricsRates.forget(nodeId)

        reconcileNodeMetricsRates()
    }

    private fun ClientSession.watchesNode(nodeId: Long) =
        subscribeNodes && (subscribeNodeIds == null || nodeId in subscribeNodeIds!!)

    /**
     * Asks every connected node for the host-metrics rate its watchers want now, and returns what
     * was sent. A session watching "every node" wants every node that is connected right now; one
     * that connects later is picked up by [onNodeConnected].
     */
    private fun reconcileNodeMetricsRates(): Map<Long, Long> {
        val connected = nodeManager.getConnectedNodes().keys.filterNot { it.agent }.map { it.id }

        val wanted = MetricsRates.wanted(
            sessions.values
                .filter { it.subscribeNodes }
                .map { session -> (session.subscribeNodeIds ?: connected) to session.nodeMetricsIntervalMs }
        )

        val commands = nodeMetricsRates.reconcile(wanted)

        commands.forEach { (nodeId, intervalMs) -> sendNodeMetricsRate(nodeId, intervalMs) }

        return commands
    }

    /** Fire and forget, and only to a node new enough to know the message. */
    private fun sendNodeMetricsRate(nodeId: Long, intervalMs: Long) {
        val node = nodeManager.getConnectedNodeById(nodeId) ?: return

        if (node.protocolVersion < NodeProtocol.NODE_METRICS_INTERVAL_VERSION) {
            return
        }

        nodeManager.sendMessage(nodeId, SetNodeMetricsIntervalMessage(intervalMs))
    }

    fun notifyNodeRemoved(nodeId: Long) {
        val text = JsonObject()
            .put("type", "nodeRemoved")
            .put("nodeId", nodeId)
            .encode()

        writeToSubscribers(text) { it.subscribeNodes }
    }

    /**
     * Pushes a managed server's process state to everyone watching that server.
     *
     * Separate from the `server` frame because it fires far more often (every start, stop, crash
     * and install step) and carries only what changed, so a panel showing a power button does not
     * have to re-read the whole row to grey it out.
     */
    fun pushServerState(
        serverId: Long,
        state: String?,
        exitCode: Int?,
        pid: Long?,
        since: Long?,
        reason: String? = null,
        adopted: Boolean = false,
        stdinAvailable: Boolean = true,
        reasonCode: String? = null,
        javaMajor: Int? = null
    ) {
        // The reason travels with the state and nowhere else: Pano does not store it, so a panel
        // that misses this frame reads it from the crash notification instead.
        val text = JsonObject()
            .put("type", "serverState")
            .put("serverId", serverId)
            .put("state", state)
            .put("exitCode", exitCode)
            .put("pid", pid)
            .put("since", since)
            .put("reason", reason)
            // Why, for a machine: `JAVA_MISSING` puts a "Download Java N and start" button on the
            // server's overview (SM-63). Kept in memory too, as the server JSON's `lastStopReason`.
            .put("reasonCode", reasonCode)
            .put("javaMajor", javaMajor)
            // Stored on the row as well, so a panel that opens later sees the same thing; here so
            // an open console greys out its input box the moment a node adopts (SM-51, §2.4.16).
            .put("adopted", adopted)
            .put("stdinAvailable", stdinAvailable)
            .encode()

        writeToRelevantSubscribers(text, serverId)
    }

    /**
     * Pushes task progress to the user who started the task and to anyone watching nodes, and —
     * for a task on a server — to everyone who shows that server (SM-68, §2.4.33).
     *
     * The first two audiences get every frame: the person who pressed "create server" is following
     * one install and must see it wherever they are in the panel, while the nodes page shows
     * everything a node is busy with regardless of who asked for it. The third is the Servers
     * modal / list (`subscribeServers`) and a page on that server (`subscribeServerId`), under the
     * same permission as the `server` frames they already get, paced to [TaskFrameThrottle]'s two
     * frames a second per task. Every task write passes through here, so this is also what keeps
     * the server JSON's `activeTask` current.
     */
    fun pushTaskProgress(task: ServerTask, transfer: com.panomc.platform.server.TaskTransfer? = null) {
        // Read before the store sees the frame: an end state forgets the mark, and the last frame
        // of a Pano plugin update must still say what it was (SM-77).
        val panoPluginUpdate = activeTaskStore.isPanoPluginUpdate(task.uuid)

        activeTaskStore.onTask(task, transfer = transfer)

        // Flat, keyed by `taskId`, which is the shape the panel's feed was written against (§2.4.3);
        // the whole row rides along under `task` for anything that wants the timestamps.
        val text = JsonObject()
            .put("type", "taskProgress")
            .put("taskId", task.id)
            .put("taskUuid", task.uuid)
            .put("serverId", task.serverId)
            .put("nodeId", task.nodeId)
            .put("kind", task.kind.name)
            .put("status", task.status.name)
            .put("percent", task.percent)
            .put("message", task.message)
            .put("error", task.error)
            // Same flag as the server JSON's `activeTask.panoPluginUpdate`, on the frame and its row.
            .put("panoPluginUpdate", panoPluginUpdate)
            // A download's bytes, size and rate for this frame; absent on every other one.
            .apply { transfer?.let { put("transfer", it.toJsonObject()) } }
            .put("task", task.toPublicJsonObject().put("panoPluginUpdate", panoPluginUpdate))
            .encode()

        writeToSubscribers(text) { isTaskOwnerAudience(it.userId, it.subscribeNodes, task.createdBy) }

        val serverId = task.serverId ?: return

        val wantsListFrame: (ClientSession) -> Boolean = {
            isTaskServerAudience(
                userId = it.userId,
                subscribeNodes = it.subscribeNodes,
                canManageServers = it.canManageServers,
                subscribeServers = it.subscribeServers,
                subscribeServerId = it.subscribeServerId,
                taskCreatedBy = task.createdBy,
                taskServerId = serverId
            )
        }

        when (val offer = taskFrameThrottle.offer(task.uuid, text, task.status.isTerminal, System.currentTimeMillis())) {
            TaskFrameThrottle.Offer.Send -> writeToSubscribers(text, wantsListFrame)

            is TaskFrameThrottle.Offer.Hold -> offer.scheduleInMs?.let { delay ->
                vertx.setTimer(delay.coerceAtLeast(1)) {
                    // Re-evaluated when it fires: somebody may have closed the modal meanwhile.
                    taskFrameThrottle.flush(task.uuid, System.currentTimeMillis())
                        ?.let { writeToSubscribers(it, wantsListFrame) }
                }
            }
        }
    }

    /**
     * Nudges everyone watching [serverId] that something under [path] changed.
     *
     * A nudge and not the new listing: two people in the same directory see each other's changes,
     * and a file manager that already knows how to list a directory needs nothing more than being
     * told to do it again. Sending the entries instead would mean pushing a directory nobody is
     * currently looking at to every subscriber.
     */
    fun pushServerFilesChanged(serverId: Long, path: String) {
        val text = JsonObject()
            .put("type", "files")
            .put("serverId", serverId)
            .put("path", path)
            .encode()

        writeToRelevantSubscribers(text, serverId)
    }

    /**
     * Nudges everyone watching [serverId] that its backup list changed.
     *
     * A nudge for the same reason the file one is: the panel already knows how to fetch the list,
     * and a backup row is small but a list of them is not worth pushing to people who are not on
     * that page.
     */
    fun pushServerBackupsChanged(serverId: Long) {
        val text = JsonObject()
            .put("type", "backups")
            .put("serverId", serverId)
            .encode()

        writeToRelevantSubscribers(text, serverId)
    }

    /** Nudges everyone watching [serverId] that its schedule list changed. */
    fun pushServerSchedulesChanged(serverId: Long) {
        val text = JsonObject()
            .put("type", "schedules")
            .put("serverId", serverId)
            .encode()

        writeToRelevantSubscribers(text, serverId)
    }

    /**
     * Tells everyone watching [serverId] that one of its schedules just ran.
     *
     * Carries the outcome rather than being a plain nudge: a schedule that failed at four in the
     * morning is the one thing about this feature somebody actually wants to be told, and the
     * panel should be able to raise it without a round trip.
     */
    fun pushScheduleRun(serverId: Long, scheduleId: Long, ok: Boolean, error: String?) {
        val text = JsonObject()
            .put("type", "scheduleRun")
            .put("serverId", serverId)
            .put("scheduleId", scheduleId)
            .put("ok", ok)
            .put("error", error)
            .encode()

        writeToRelevantSubscribers(text, serverId)
    }

    /**
     * Nudges everyone watching [serverId]'s plugins that its jar files changed.
     *
     * Deliberately not [pushInstalledPlugins]: a jar that was just installed is not loaded yet, so
     * there is no new plugin list to send — the panel has to re-read the endpoint to learn that a
     * file appeared and that the server now needs a restart.
     */
    fun pushServerPluginsChanged(serverId: Long) {
        val text = JsonObject()
            .put("type", "plugins")
            .put("serverId", serverId)
            .encode()

        writeToSubscribers(text) { it.subscribePluginsServerId == serverId }
    }

    /** Fans a refreshed plugin list out to everyone watching [serverId]'s plugins. */
    fun pushInstalledPlugins(serverId: Long, plugins: List<ServerPluginData>) {
        val text = JsonObject()
            .put("type", "plugins")
            .put("serverId", serverId)
            .put("plugins", JsonArray(plugins.map { it.toJsonObject() }))
            .encode()

        writeToSubscribers(text) { it.subscribePluginsServerId == serverId }
    }

    // Shared plumbing for the per-server subscriptions that are a straight permission check with
    // no side effects beyond remembering what this socket wants.
    /**
     * Asks every server for the rate its metrics subscribers want now, and returns what was sent.
     *
     * Worked out from the live [sessions] every time rather than kept as a running count, because a
     * session can disappear from several places and a count that missed one would keep a server
     * reporting every second for nobody.
     */
    private fun reconcileMetricsRates(): Map<Long, Long> {
        val wanted = MetricsRates.wanted(
            sessions.values.map { session ->
                (session.subscribeMetricsServerIds + listOfNotNull(session.subscribeMetricsServerId)) to session.metricsIntervalMs
            }
        )

        val commands = metricsRates.reconcile(wanted)

        commands.forEach { (serverId, intervalMs) -> sendMetricsRate(serverId, intervalMs) }

        return commands
    }

    /**
     * Tells whatever reports [serverId]'s metrics to do so every [intervalMs] (§2.4.23 A): its
     * plugin when one is connected, its node when it is a managed server — both when both report,
     * because both do and each only hears its own command.
     *
     * Fire and forget. A source that is offline or too old to know the command simply keeps its
     * default, and the lease re-sends within a minute to one that comes back.
     */
    private fun sendMetricsRate(serverId: Long, intervalMs: Long) {
        if (serverManager.isConnected(serverId)) {
            serverManager.sendMessage(serverId, PluginSetMetricsIntervalMessage(intervalMs))
        }

        CoroutineScope(vertx.dispatcher()).launch {
            val server = try {
                databaseManager.serverDao.getById(serverId, databaseManager.getSqlClient())
            } catch (_: Exception) {
                null
            } ?: return@launch

            val nodeId = server.nodeId ?: return@launch
            val uuid = server.uuid ?: return@launch

            if (server.isManaged) {
                nodeManager.sendMessage(nodeId, NodeSetMetricsIntervalMessage(uuid, intervalMs))
            }
        }
    }

    private fun applyScopedSubscription(
        socket: ServerWebSocket,
        session: ClientSession,
        serverId: Long?,
        permission: Permission,
        current: () -> Long?,
        assign: (Long?) -> Unit
    ) {
        if (serverId == null) {
            assign(null)

            return
        }

        CoroutineScope(vertx.dispatcher()).launch {
            val allowed = hasServerPermission(session.userId, permission, serverId)

            // The socket may have closed, or the client may have resubscribed, while the
            // permission lookup was in flight.
            if (sessions[socket] !== session) {
                return@launch
            }

            if (!allowed) {
                if (current() != null) {
                    assign(null)
                }

                return@launch
            }

            assign(serverId)
        }
    }

    /**
     * Replaces the servers [session] watches all at once with those of [serverIds] its user may
     * see the metrics of — the same per-server check a single `subscribeMetricsServerId` gets, one
     * id at a time, and the ids it fails simply left out.
     */
    private fun applyMetricsServerIdsSubscription(socket: ServerWebSocket, session: ClientSession, serverIds: List<Long>) {
        val generation = ++session.metricsServerIdsGeneration

        if (serverIds.isEmpty()) {
            session.subscribeMetricsServerIds = emptySet()

            reconcileMetricsRates()

            return
        }

        CoroutineScope(vertx.dispatcher()).launch {
            val admin = try {
                authProvider.isUserAdmin(session.userId)
            } catch (_: Exception) {
                false
            }

            val allowed = if (admin) {
                serverIds.toSet()
            } else {
                serverIds.filterTo(LinkedHashSet()) { serverId ->
                    hasServerPermission(session.userId, ManageServersPermission(), serverId)
                }
            }

            // Closed, or asked for a different set, while the checks were in flight.
            if (sessions[socket] !== session || session.metricsServerIdsGeneration != generation) {
                return@launch
            }

            session.subscribeMetricsServerIds = allowed

            reconcileMetricsRates()
        }
    }

    private suspend fun hasServerPermission(userId: Long, permission: Permission, serverId: Long) = try {
        authProvider.isUserAdmin(userId) || permissionManager.hasPermission(userId, permission, serverId)
    } catch (_: Exception) {
        false
    }

    private fun applyConsoleSubscription(socket: ServerWebSocket, session: ClientSession, serverId: Long?) {
        if (serverId == null) {
            clearConsoleSubscription(session)

            return
        }

        CoroutineScope(vertx.dispatcher()).launch {
            val allowed = hasServerPermission(session.userId, ManageServerConsolePermission(), serverId)

            // The socket may have closed, or the client may have resubscribed, while the
            // permission lookup was in flight.
            if (sessions[socket] !== session) {
                return@launch
            }

            if (!allowed) {
                clearConsoleSubscription(session)

                return@launch
            }

            val previous = session.subscribeConsoleServerId

            session.subscribeConsoleServerId = serverId

            if (previous != null && previous != serverId) {
                reconcileConsoleStream(previous)
            }

            reconcileConsoleStream(serverId)

            writeConsoleState(socket, serverId)
        }
    }

    private fun clearConsoleSubscription(session: ClientSession) {
        val previous = session.subscribeConsoleServerId

        session.subscribeConsoleServerId = null

        if (previous != null) {
            reconcileConsoleStream(previous)
        }
    }

    // Brings the plugin's stream state in line with how many panels are watching right now.
    private fun reconcileConsoleStream(serverId: Long) {
        if (consoleSubscriberCount(serverId) > 0) {
            cancelStopStreamTimer(serverId)

            // add() returns true only on the 0 -> 1 transition, so the plugin gets exactly one
            // CONSOLE_STREAM true no matter how many panels pile on.
            if (streamingServers.add(serverId)) {
                requestConsoleStream(serverId, true)

                broadcastConsoleState(serverId)
            }

            return
        }

        if (!streamingServers.contains(serverId) || stopStreamTimers.containsKey(serverId)) {
            return
        }

        val timerId = vertx.setTimer(CONSOLE_STREAM_STOP_DELAY_MS) {
            stopStreamTimers.remove(serverId)

            if (consoleSubscriberCount(serverId) > 0) {
                return@setTimer
            }

            if (streamingServers.remove(serverId)) {
                requestConsoleStream(serverId, false)
            }
        }

        stopStreamTimers[serverId] = timerId
    }

    private fun cancelStopStreamTimer(serverId: Long) {
        val timerId = stopStreamTimers.remove(serverId) ?: return

        vertx.cancelTimer(timerId)
    }

    /**
     * Which of a server's two possible console streams is the one Pano forwards right now.
     *
     * A managed server can be talking to Pano twice at once: the node pipes the process's stdout
     * and the Pano plugin inside the game reports the same lines through its log appender. Both
     * were forwarded at first, which meant a managed console showed every line twice — the same
     * text with two `src` flags, a second apart. Rather than fold duplicates after the fact, which
     * cannot be done reliably (a server legitimately prints the same line twice), one stream wins.
     *
     * The node wins whenever it is there, because its stream is strictly larger: it starts at the
     * JVM's first line, survives the plugin failing to load, and keeps going through the stack
     * trace that killed it. The plugin only covers the window in which the game is healthy, so it
     * is the fallback — for linked servers, which have no node at all, and for a managed server
     * whose node went offline while the game kept running.
     */
    suspend fun activeConsoleSource(serverId: Long): String {
        val server = databaseManager.serverDao.getById(serverId, databaseManager.getSqlClient())
            ?: return ConsoleLineData.SRC_PLUGIN

        return activeConsoleSource(server)
    }

    /** [activeConsoleSource] for a row the caller already has. */
    fun activeConsoleSource(server: Server): String {
        val nodeId = server.nodeId

        return if (server.isManaged && nodeId != null && nodeManager.isConnected(nodeId)) {
            ConsoleLineData.SRC_NODE
        } else {
            ConsoleLineData.SRC_PLUGIN
        }
    }

    /**
     * Re-decides the console source of every server on [nodeId] that someone is watching.
     *
     * Called when a node connects or drops. The switch has to reach the senders and not only the
     * merge point: a plugin that is never told to stop keeps pushing batches Pano would throw
     * away, and a plugin that is never told to start leaves a console blank after its node dies
     * even though the game is still running and still has something to say.
     */
    fun onNodeConsoleAvailabilityChanged(nodeId: Long) {
        CoroutineScope(vertx.dispatcher()).launch {
            val sqlClient = databaseManager.getSqlClient()

            streamingServers.toList().forEach { serverId ->
                val server = databaseManager.serverDao.getById(serverId, sqlClient) ?: return@forEach

                if (server.nodeId != nodeId) {
                    return@forEach
                }

                applyConsoleStreamRequest(serverId, true)

                writeToConsoleSubscribers(buildConsoleStateFrame(serverId), serverId)
            }
        }
    }

    /**
     * Asks again for the console of [serverId] when someone is watching it, after its node set the
     * server up anew (an install, reinstall, import or restore that ended DONE).
     *
     * A node before 2026-09-25 kept the switch on the process object the install replaced -- or had
     * no object at all when the install had failed -- so the console stayed blank after the next
     * start until the page was reloaded. A current node remembers it by uuid; asking twice is
     * harmless, because turning on a stream that is already on does nothing.
     */
    fun onServerReinstalled(serverId: Long) {
        if (streamingServers.contains(serverId)) {
            requestConsoleStream(serverId, true)

            broadcastConsoleState(serverId)
        }
    }

    private fun requestConsoleStream(serverId: Long, enabled: Boolean) {
        CoroutineScope(vertx.dispatcher()).launch {
            applyConsoleStreamRequest(serverId, enabled)
        }
    }

    // Both senders are addressed on every change, including the one that is being switched off:
    // the loser has to be told to stop, or it keeps batching lines into a socket for nothing.
    private suspend fun applyConsoleStreamRequest(serverId: Long, enabled: Boolean) {
        val sqlClient = databaseManager.getSqlClient()
        val server = databaseManager.serverDao.getById(serverId, sqlClient)

        val source = if (server == null) ConsoleLineData.SRC_PLUGIN else activeConsoleSource(server)

        serverManager.sendMessage(
            serverId,
            ConsoleStreamMessage(enabled && source == ConsoleLineData.SRC_PLUGIN)
        )

        if (server == null || !server.isManaged) {
            return
        }

        val nodeId = server.nodeId ?: return
        val uuid = server.uuid ?: return

        nodeManager.sendMessage(nodeId, NodeConsoleStreamMessage(uuid, enabled))
    }

    private fun consoleSubscriberCount(serverId: Long): Int {
        var count = 0

        for ((ws, s) in sessions.toList()) {
            if (ws.isClosed) {
                sessions.remove(ws)

                continue
            }

            if (s.subscribeConsoleServerId == serverId) {
                count++
            }
        }

        return count
    }

    private fun broadcastConsoleState(serverId: Long) {
        CoroutineScope(vertx.dispatcher()).launch {
            writeToConsoleSubscribers(buildConsoleStateFrame(serverId), serverId)
        }
    }

    private suspend fun writeConsoleState(socket: ServerWebSocket, serverId: Long) {
        val text = buildConsoleStateFrame(serverId)

        try {
            socket.writeTextMessage(text)
        } catch (_: Exception) {
            sessions.remove(socket)
        }
    }

    private suspend fun buildConsoleStateFrame(serverId: Long): String {
        val source = activeConsoleSource(serverId)

        // Liveness is asked of whichever side is actually feeding the console. Asking the plugin
        // in every case is how a managed server whose node streams perfectly well but whose plugin
        // is not loaded ends up reporting a console that is not streaming.
        val sourceConnected = source == ConsoleLineData.SRC_NODE || serverManager.isConnected(serverId)

        return JsonObject()
            .put("type", "consoleState")
            .put("serverId", serverId)
            .put("streaming", streamingServers.contains(serverId) && sourceConnected)
            // Which stream the lines below are coming from, so the panel can say so rather than
            // leaving a reader to work it out from the per-line `src` flags.
            .put("source", source)
            .put("capable", isConsoleCapable(serverId))
            .encode()
    }

    // Capabilities live on the server row, so an offline server still reports what it could do the
    // last time it connected. That is what lets the panel show the console page (with history) and
    // only disable the input while the server is down.
    private suspend fun isConsoleCapable(serverId: Long): Boolean {
        val server = databaseManager.serverDao.getById(serverId, databaseManager.getSqlClient())
            ?: serverManager.getConnectedServerById(serverId)
            ?: return false

        // A managed server always has a console: the node pipes its stdout whether or not a plugin
        // is installed, which is the whole point of owning the process.
        return server.isManaged || server.hasCapability(ServerCapability.CONSOLE)
    }

    private fun writeToConsoleSubscribers(message: String, serverId: Long) {
        writeToSubscribers(message) { it.subscribeConsoleServerId == serverId }
    }

    private fun writeToSubscribers(message: String, wants: (ClientSession) -> Boolean) {
        for ((ws, s) in sessions.toList()) {
            if (ws.isClosed) {
                sessions.remove(ws)
                continue
            }
            if (!wants(s)) {
                continue
            }
            try {
                ws.writeTextMessage(message)
            } catch (_: Exception) {
                sessions.remove(ws)
            }
        }
    }

    private fun hasSubscriber(wants: (ClientSession) -> Boolean): Boolean {
        for ((ws, s) in sessions.toList()) {
            if (ws.isClosed) {
                sessions.remove(ws)
                continue
            }
            if (wants(s)) {
                return true
            }
        }

        return false
    }

    /**
     * Tells the panels watching [serverId] that its activity feed has a new entry, as
     * `{ "type": "serverActivity", "serverId": id }`; they re-fetch `GET /servers/:id/activity`.
     *
     * Only to sessions subscribed to that server with `subscribeServerId` and holding the
     * permission the activity endpoint requires (MANAGE_SERVERS). Coalesced over
     * [ACTIVITY_COALESCE_MS]: a burst of entries (a multi-file delete, a plugin install) is one
     * re-fetch, and the frame lands after the transaction that wrote the entry has committed.
     */
    fun notifyServerActivity(serverId: Long) {
        if (!pendingActivity.add(serverId)) {
            return
        }

        vertx.setTimer(ACTIVITY_COALESCE_MS) {
            pendingActivity.remove(serverId)

            val text = JsonObject()
                .put("type", "serverActivity")
                .put("serverId", serverId)
                .encode()

            writeToSubscribers(text) { it.canManageServers && it.subscribeServerId == serverId }
        }
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

    companion object {
        /** Who gets every `taskProgress` frame: the task's creator and anyone watching nodes. */
        fun isTaskOwnerAudience(userId: Long, subscribeNodes: Boolean, taskCreatedBy: Long) =
            userId == taskCreatedBy || subscribeNodes

        /**
         * Who gets the paced `taskProgress` frames of a task on server [taskServerId] (SM-68): a
         * session that already shows the server (the list, or a page on it) with the permission
         * its `server` frames need, and that is not already an [isTaskOwnerAudience] — nobody gets
         * the same frame twice.
         */
        fun isTaskServerAudience(
            userId: Long,
            subscribeNodes: Boolean,
            canManageServers: Boolean,
            subscribeServers: Boolean,
            subscribeServerId: Long?,
            taskCreatedBy: Long,
            taskServerId: Long
        ) = !isTaskOwnerAudience(userId, subscribeNodes, taskCreatedBy) &&
            canManageServers &&
            (subscribeServers || subscribeServerId == taskServerId)

        /** How long `serverActivity` frames for one server are gathered into one. */
        const val ACTIVITY_COALESCE_MS = 250L

        /**
         * Grace period before the plugin is told to stop streaming after the last viewer leaves.
         * Long enough to cover a page reload or a tab switch without a stop/start round trip.
         */
        private const val CONSOLE_STREAM_STOP_DELAY_MS = 30_000L
    }
}
