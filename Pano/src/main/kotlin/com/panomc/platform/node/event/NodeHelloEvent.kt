package com.panomc.platform.node.event

import com.panomc.platform.annotation.Event
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.Node
import com.panomc.platform.node.NodeEvent
import com.panomc.platform.node.AgentServerLinkService
import com.panomc.platform.node.NodeDaemonUpdateService
import com.panomc.platform.node.NodeEventResponse
import com.panomc.platform.node.NodeJavaSupport
import com.panomc.platform.node.NodeManager
import com.panomc.platform.node.NodePendingDeletions
import com.panomc.platform.node.NodeProtocol
import com.panomc.platform.node.NodeRuntime
import com.panomc.platform.node.NodeServerStateReconciler
import com.panomc.platform.node.NodeUpdateProgressService
import com.panomc.platform.node.ServerPluginStateService
import com.panomc.platform.node.dto.NodeResources
import com.panomc.platform.node.event.request.NodeHelloEventRequest
import com.panomc.platform.node.message.DeleteServerMessage
import com.panomc.platform.panel.PanelRealtimeHub
import com.panomc.platform.server.ProcessStartTime
import com.panomc.platform.server.ServerProcessState
import com.panomc.platform.server.ServerStopReasonStore
import com.panomc.platform.server.ServerTimeZone
import com.panomc.platform.server.alert.AlertManager
import com.panomc.platform.server.schedule.ServerScheduleService
import org.slf4j.Logger
import java.util.UUID

/**
 * First message of every node connection (`NODE_HELLO`): who the node is and what it is running.
 *
 * Two jobs. It refreshes the node row with the host's facts, and it reconciles the process states
 * the node reports against what Pano believes — the only chance to close the gap that opens
 * whenever either side restarts. Reconciliation is the node's word against the database's, and the
 * node wins: it is the one holding the process handles.
 */
@Event
class NodeHelloEvent(
    private val databaseManager: DatabaseManager,
    private val nodeManager: NodeManager,
    private val panelRealtimeHub: PanelRealtimeHub,
    private val serverScheduleService: ServerScheduleService,
    private val serverPluginStateService: ServerPluginStateService,
    private val alertManager: AlertManager,
    private val serverStopReasonStore: ServerStopReasonStore,
    private val nodeDaemonUpdateService: NodeDaemonUpdateService,
    private val agentServerLinkService: AgentServerLinkService,
    private val nodeUpdateProgressService: NodeUpdateProgressService,
    private val logger: Logger
) : NodeEvent<NodeHelloEventRequest, NodeEventResponse>() {
    override suspend fun handle(request: NodeHelloEventRequest, node: Node): NodeEventResponse? {
        val sqlClient = databaseManager.getSqlClient()
        val now = System.currentTimeMillis()

        val resources = NodeResources.fromReported(
            cpuCores = request.cpuCores,
            memTotal = request.memTotal,
            diskTotal = request.diskTotal,
            javaRuntimes = request.javaRuntimes,
            javaAutoDownload = request.javaAutoDownload,
            javaDownloads = NodeJavaSupport.supportsDownloads(
                request.protocolVersion,
                request.javaAutoDownload,
                request.capabilities
            )
        )

        node.version = request.version?.take(MAX_SHORT_FIELD)
        node.protocolVersion = request.protocolVersion ?: NodeProtocol.LEGACY_PROTOCOL_VERSION
        node.os = request.os?.take(MAX_SHORT_FIELD)
        node.arch = request.arch?.take(MAX_SHORT_FIELD)
        node.dataPath = request.dataPath?.take(MAX_PATH_FIELD)
        // Anything but a runtime Pano knows leaves the stored one alone: a node that reported
        // nothing has not said it went back to PROCESS. This used to be assigned and then dropped
        // on the floor — the row was never written, so a daemon restarted with
        // PANO_NODE_RUNTIME=DOCKER kept showing as PROCESS in the panel forever.
        node.runtime = NodeRuntime.fromIdOrNull(request.runtime) ?: node.runtime
        node.resources = resources
        node.lastSeen = now

        databaseManager.nodeDao.updateHelloById(
            id = node.id,
            version = node.version,
            protocolVersion = node.protocolVersion,
            os = node.os,
            arch = node.arch,
            dataPath = node.dataPath,
            runtime = node.runtime,
            resources = resources,
            lastSeen = now,
            sqlClient = sqlClient
        )

        // Kept off the row on purpose: it describes the process on the other end of *this* socket,
        // so it belongs to the connection rather than to the node's stored identity.
        nodeManager.setJarSha256(node.id, request.jarSha256)

        // Before anything below can create a server on this node (a Pano Agent's link does): its
        // port is allocated inside this range, the one a container actually publishes.
        nodeManager.setPortRange(node.id, request.portRange?.toRangeOrNull())

        // The hello after an update's restart is the new daemon: its progress ends DONE (SM-77).
        nodeUpdateProgressService.onHello(node, node.version, request.jarSha256)

        // Back from wherever it was: the open offline alert is closed and its cooldown cleared, so
        // the next outage is reported at once rather than being swallowed as a repeat.
        alertManager.onNodeOnline(node.id, sqlClient)

        reconcileServerStates(request, node)

        deletePendingServers(request, node)

        // A Pano Agent's first hello creates and adopts its one server; later ones retry an
        // adoption that failed. Nothing for an ordinary node.
        try {
            agentServerLinkService.onHello(node, request)
        } catch (e: Exception) {
            logger.warn("Could not link the server of Pano Agent ${node.id}: ${e.message}")
        }

        // The node's schedules are replaced wholesale on every connect. It may have been offline
        // through an edit, or it may have restarted with none at all, and this is the one moment
        // where that can be made right without anybody noticing.
        serverScheduleService.syncNode(node.id, sqlClient)

        // A daemon that just restarted has no idea which of its servers has a Pano plugin talking
        // to Pano, and it decides whether to ping on exactly that (SM-52, §2.4.17 B).
        serverPluginStateService.syncNode(node.id, sqlClient)

        panelRealtimeHub.notifyNodeUpdated(node.id)

        // After the protocol version is known: the rate is only sent to nodes that understand it.
        panelRealtimeHub.onNodeConnected(node.id)

        // Last, and in the background: an update restarts the daemon, and everything above is what
        // this hello was for. Nothing happens unless managed-servers.node-auto-update is on and
        // the node runs an older daemon than this Pano serves.
        nodeDaemonUpdateService.onHello(node)

        return null
    }

    private suspend fun reconcileServerStates(request: NodeHelloEventRequest, node: Node) {
        val sqlClient = databaseManager.getSqlClient()

        // Only this node's servers are considered, in both directions: a uuid the node made up
        // cannot enter this set, and a server belonging to another node cannot be moved by it.
        val known = databaseManager.serverDao.getAllByNodeId(node.id, sqlClient)

        val reported = (request.servers ?: emptyList())
            .mapNotNull { entry ->
                entry.uuid?.let {
                    it to NodeServerStateReconciler.Reported(
                        state = entry.state,
                        // A node too old to report either flag is a node that cannot adopt
                        // anything, so its silence means "started by me, stdin works".
                        adopted = entry.adopted ?: false,
                        stdinAvailable = entry.stdinAvailable ?: true,
                        exitCode = entry.exitCode
                    )
                }
            }
            .toMap()

        val changes = NodeServerStateReconciler.reconcile(
            known.mapNotNull { server ->
                server.uuid?.let {
                    NodeServerStateReconciler.Known(
                        serverId = server.id,
                        uuid = it,
                        processState = server.processState,
                        adopted = server.adopted,
                        stdinAvailable = server.stdinAvailable,
                        lastExitCode = server.lastExitCode
                    )
                }
            },
            reported
        )

        changes.forEach { change ->
            // A server the node reports alive again has no failed start left to explain.
            if (change.state.isAlive) {
                serverStopReasonStore.remove(change.serverId)
            }

            databaseManager.serverDao.updateProcessStateById(change.serverId, change.state, change.exitCode, sqlClient)
            databaseManager.serverDao.updateAdoptionById(
                change.serverId,
                change.adopted,
                change.stdinAvailable,
                sqlClient
            )

            panelRealtimeHub.pushServerState(
                serverId = change.serverId,
                state = change.state.name,
                exitCode = change.exitCode,
                // The node follows its hello with a SERVER_STATE for everything it adopted, and
                // that frame is where the pid and the uptime come from.
                pid = null,
                since = null,
                adopted = change.adopted,
                stdinAvailable = change.stdinAvailable
            )
            panelRealtimeHub.notifyServerUpdated(change.serverId)
        }

        if (changes.isNotEmpty()) {
            logger.info("Reconciled ${changes.size} managed server state(s) after \"${node.name}\" node hello.")
        }

        // Where each server lives, and whether it was adopted there (protocol 5). The node is the
        // authority; only a node that says something changes the row, and only when it differs.
        (request.servers ?: emptyList()).forEach { entry ->
            if (entry.inPlace == null && entry.directory == null) {
                return@forEach
            }

            val server = known.firstOrNull { it.uuid != null && it.uuid == entry.uuid } ?: return@forEach

            val inPlace = entry.inPlace ?: server.inPlace
            val directory = entry.directory?.take(ImportResultEvent.MAX_DIRECTORY_LENGTH) ?: server.directory

            if (inPlace == server.inPlace && directory == server.directory) {
                return@forEach
            }

            databaseManager.serverDao.updateLocationById(server.id, inPlace, directory, sqlClient)

            panelRealtimeHub.notifyServerUpdated(server.id)
        }

        // The host's time zone, for every server of this node that no plugin speaks for (§2.4.25).
        known.forEach { server ->
            val zone = ServerTimeZone.fromNode(server.timeZone, server.pluginVersion, request.timeZone)
                ?: return@forEach

            databaseManager.serverDao.updateTimeZoneById(server.id, zone, sqlClient)

            panelRealtimeHub.notifyServerUpdated(server.id)
        }

        // The start time of every server the node reports RUNNING (SM-57's Uptime). A hello follows
        // every Pano restart, so this is also how servers that were already running before the
        // column existed get one, without waiting for their next restart.
        val notified = changes.map { it.serverId }.toSet()
        val now = System.currentTimeMillis()

        (request.servers ?: emptyList())
            .filter { ServerProcessState.fromId(it.state) == ServerProcessState.RUNNING }
            .forEach { entry ->
                val server = known.firstOrNull { it.uuid != null && it.uuid == entry.uuid } ?: return@forEach
                val startedAt = ProcessStartTime.whenRunning(entry.startedAt, entry.since, now)

                if (startedAt == server.processStartedAt) {
                    return@forEach
                }

                databaseManager.serverDao.updateProcessStartedAtById(server.id, startedAt, sqlClient)

                if (server.id !in notified) {
                    panelRealtimeHub.notifyServerUpdated(server.id)
                }
            }
    }

    /**
     * Asks the node to delete the servers Pano force-deleted while it could not (SM-64, §2.4.29 A).
     *
     * Only uuids on the pending list are ever named — never "everything the node has that Pano has
     * no row for", which would also delete a server whose row went missing for any other reason.
     * No task row is written: the server is already gone from Pano, and the node's progress frames
     * for a task id Pano never opened are dropped, so the pending entry is what tracks it until
     * the node stops reporting the uuid.
     */
    private suspend fun deletePendingServers(request: NodeHelloEventRequest, node: Node) {
        val sqlClient = databaseManager.getSqlClient()

        val pending = databaseManager.nodePendingDeletionDao.getAllByNodeId(node.id, sqlClient)

        if (pending.isEmpty()) {
            return
        }

        val reported = request.servers.orEmpty().mapNotNull { it.uuid }.toSet()
        val known = databaseManager.serverDao.getAllByNodeId(node.id, sqlClient).mapNotNull { it.uuid }.toSet()

        val plan = NodePendingDeletions.plan(pending.map { it.serverUuid }, reported, known)

        plan.settled.forEach { uuid ->
            databaseManager.nodePendingDeletionDao.deleteByNodeIdAndServerUuid(node.id, uuid, sqlClient)
        }

        plan.delete.forEach { uuid ->
            if (nodeManager.sendMessage(node.id, DeleteServerMessage(uuid, UUID.randomUUID().toString()))) {
                logger.info("Asked \"${node.name}\" node to delete server $uuid, which was removed from Pano while it could not.")
            }
        }
    }

    companion object {
        private const val MAX_SHORT_FIELD = 64
        private const val MAX_PATH_FIELD = 512
    }
}
