package com.panomc.platform.node.event

import com.panomc.platform.annotation.Event
import com.panomc.platform.auth.panel.log.ServerCrashedLog
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.Node
import com.panomc.platform.node.NodeEvent
import com.panomc.platform.node.NodeEventResponse
import com.panomc.platform.node.NodeManager
import com.panomc.platform.node.event.request.ServerStateEventRequest
import com.panomc.platform.panel.PanelRealtimeHub
import com.panomc.platform.server.alert.AlertManager
import com.panomc.platform.server.console.ServerCrashReason
import com.panomc.platform.server.ServerManager
import com.panomc.platform.server.ProcessStartTime
import com.panomc.platform.server.ServerProcessState
import com.panomc.platform.server.ServerStopReasonStore

/**
 * A managed server's process changed state (`SERVER_STATE`).
 *
 * CRASHED is the state that earns this event its complexity: it is the one thing an admin cannot
 * find out any other way, so it is both audited and pushed as a notification to everyone who can
 * manage servers. The other transitions are ordinary bookkeeping and only reach the panels that
 * are currently looking.
 */
@Event
class ServerStateEvent(
    private val databaseManager: DatabaseManager,
    private val nodeManager: NodeManager,
    private val panelRealtimeHub: PanelRealtimeHub,
    private val serverManager: ServerManager,
    private val alertManager: AlertManager,
    private val serverStopReasonStore: ServerStopReasonStore
) : NodeEvent<ServerStateEventRequest, NodeEventResponse>() {
    override suspend fun handle(request: ServerStateEventRequest, node: Node): NodeEventResponse? {
        val sqlClient = databaseManager.getSqlClient()

        val server = nodeManager.resolveServer(node, request.serverUuid, sqlClient) ?: return null
        val state = ServerProcessState.fromId(request.state) ?: return null

        val previous = server.processState

        // Only a real exit carries an exit code; keeping the old one around on a start would make
        // "why did it stop last time" answer the wrong question.
        val exitCode = if (state == ServerProcessState.STOPPED || state == ServerProcessState.CRASHED) {
            request.exitCode
        } else {
            null
        }

        databaseManager.serverDao.updateProcessStateById(server.id, state, exitCode, sqlClient)

        // Nothing measures a process that is gone: the last sample must not outlive it.
        if (!state.isAlive) {
            serverManager.clearLatestMetrics(server.id)
        }

        // The uptime of a managed server with no plugin (SM-57): set from the node's own start time
        // when the process is RUNNING, and cleared by the update above when it stops or crashes.
        if (state == ServerProcessState.RUNNING) {
            val startedAt = ProcessStartTime.whenRunning(request.startedAt, request.since, System.currentTimeMillis())

            if (startedAt != server.processStartedAt) {
                databaseManager.serverDao.updateProcessStartedAtById(server.id, startedAt, sqlClient)
            }
        }

        // A node too old to report either flag cannot have adopted anything, so its silence is
        // read as "this is an ordinary process of mine" rather than left as whatever was there.
        val adopted = request.adopted ?: false
        val stdinAvailable = request.stdinAvailable ?: true

        if (adopted != server.adopted || stdinAvailable != server.stdinAvailable) {
            databaseManager.serverDao.updateAdoptionById(server.id, adopted, stdinAvailable, sqlClient)
        }

        // What the node read off the console, or — for a node too old to send one — whatever
        // Pano's own buffer can still explain. "Exit code 1" on its own tells an admin nothing
        // they could not already see. Only an unexpected stop has one; a start never does.
        //
        // A STOPPED carries the node's reason too, but only as sent: that is how a start the node
        // refused (no Java, SM-63) explains itself, and a stop somebody asked for has none — the
        // console buffer is not searched for one, since a clean shutdown would find an error line
        // to blame from an hour ago.
        val reason = when (state) {
            ServerProcessState.CRASHED -> ServerCrashReason.clean(request.reason)
                ?: ServerCrashReason.of(serverManager.getConsoleBuffer(server.id).snapshot(CRASH_LINES))

            ServerProcessState.STOPPED -> ServerCrashReason.clean(request.reason)

            else -> null
        }

        val reasonCode = if (state == ServerProcessState.STOPPED || state == ServerProcessState.CRASHED) {
            ServerStopReasonStore.cleanCode(request.reasonCode)
        } else {
            null
        }
        val javaMajor = reasonCode?.let { ServerStopReasonStore.cleanMajor(request.javaMajor) }

        serverStopReasonStore.onState(server.id, state, reason, reasonCode, javaMajor)

        panelRealtimeHub.pushServerState(
            serverId = server.id,
            state = state.name,
            exitCode = exitCode,
            pid = request.pid,
            since = request.since,
            reason = reason,
            adopted = adopted,
            stdinAvailable = stdinAvailable,
            reasonCode = reasonCode,
            javaMajor = javaMajor
        )
        panelRealtimeHub.notifyServerUpdated(server.id)

        if (state == ServerProcessState.CRASHED && previous != ServerProcessState.CRASHED) {
            val name = server.customName ?: server.name

            databaseManager.panelActivityLogDao.add(ServerCrashedLog(server.id, name, exitCode), sqlClient)

            // Through the alert manager rather than straight to a notification, so a server that
            // crash-loops is reported once rather than every few seconds, and so an operator who
            // turned this kind off actually stops hearing about it.
            alertManager.onServerCrashed(server, exitCode, reason, sqlClient)
        }

        return null
    }

    companion object {
        /** How far back Pano looks for a reason when the node did not send one. */
        private const val CRASH_LINES = 50
    }
}
