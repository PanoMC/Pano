package com.panomc.platform.node

import com.panomc.platform.db.model.Node
import com.panomc.platform.node.event.request.TaskProgressEventRequest
import com.panomc.platform.panel.PanelRealtimeHub
import io.vertx.core.Vertx
import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.context.annotation.Lazy
import org.springframework.context.annotation.Scope
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap

/**
 * Feeds [NodeUpdateProgressStore] and tells the panels about every change (SM-77).
 *
 * The four things that move a daemon update along each call in here: [onStarted]
 * (`NodeDaemonUpdateService.update`, which the nodes page, a Pano Agent server's update and the
 * automatic update all go through), [onFrame] (the node's `SELF_UPDATE` progress), [onDisconnect]
 * (`NodeManager`) and [onHello]. The clock -- DONE and FAILED going away, a staged update that
 * never restarted -- is a sweep that only runs while there is something to sweep.
 *
 * Every change goes out as the frames the panel already follows: the `node` frame for the nodes
 * page (its `updateProgress`) and a `server` frame for every server on that node (their
 * `daemonUpdate`), so every tab of every user on those pages sees the same bar. A Pano Agent has no
 * node frame; `notifyNodeUpdated` sends its server's frame instead. Percentages are paced to one
 * push per [PUSH_INTERVAL_MS] per node -- each push re-reads every server of that node -- and a
 * change of state always goes out at once.
 */
@Lazy
@Component
@Scope(value = ConfigurableBeanFactory.SCOPE_SINGLETON)
class NodeUpdateProgressService(
    private val vertx: Vertx,
    private val store: NodeUpdateProgressStore,
    private val panelRealtimeHub: PanelRealtimeHub,
    private val agentNodeDirectory: AgentNodeDirectory
) {
    private val lastPushAt = ConcurrentHashMap<Long, Long>()

    /** Nodes with a paced push already armed. */
    private val pushPending = ConcurrentHashMap.newKeySet<Long>()

    private val sweepLock = Any()

    /** The sweep's periodic timer, or -1 while the store is empty. Only touched under [sweepLock]. */
    private var sweepTimer = -1L

    /** Pano just sent `SELF_UPDATE` to [nodeId] offering [version] ([sha256]). */
    fun onStarted(nodeId: Long, node: Node?, version: String?, sha256: String?) {
        store.start(
            nodeId = nodeId,
            nodeName = node?.name,
            agent = node?.agent ?: agentNodeDirectory.isAgent(nodeId),
            version = version,
            sha256 = sha256,
            now = System.currentTimeMillis()
        )

        changed(nodeId, immediate = true)
    }

    /**
     * A `TASK_PROGRESS` frame of kind `SELF_UPDATE` from [node]. It has no server and no task row:
     * its `taskId` is the version the node is installing.
     */
    fun onFrame(node: Node, request: TaskProgressEventRequest) {
        val before = store.get(node.id)

        val after = store.onFrame(
            nodeId = node.id,
            nodeName = node.name,
            agent = node.agent,
            version = request.taskId,
            status = request.status,
            percent = request.percent,
            message = request.message,
            error = request.error,
            now = System.currentTimeMillis()
        ) ?: return

        changed(node.id, immediate = before == null || before.status != after.status || before.staged != after.staged)
    }

    /** [nodeId]'s connection closed; see [NodeUpdateProgressStore.onDisconnect]. */
    fun onDisconnect(nodeId: Long) {
        store.onDisconnect(nodeId, System.currentTimeMillis()) ?: return

        changed(nodeId, immediate = true)
    }

    /** [node] said hello; see [NodeUpdateProgressStore.onHello]. */
    fun onHello(node: Node, version: String?, jarSha256: String?) {
        store.onHello(node.id, version, jarSha256, System.currentTimeMillis()) ?: return

        changed(node.id, immediate = true)
    }

    /**
     * Whether [nodeId] is in the middle of an update Pano sent it: downloading, or restarting onto
     * the new jar. Its disconnect then is the update, not an outage.
     */
    fun isUpdating(nodeId: Long): Boolean = store.get(nodeId)?.status.let {
        it == NodeUpdateProgressStore.Status.RUNNING || it == NodeUpdateProgressStore.Status.RESTARTING
    }

    /** Forgets [nodeId]; for a node that was deleted. Nothing is pushed: the node is gone. */
    fun forget(nodeId: Long) {
        store.forget(nodeId)

        lastPushAt.remove(nodeId)
    }

    private fun changed(nodeId: Long, immediate: Boolean) {
        ensureSweep()

        push(nodeId, immediate)
    }

    private fun push(nodeId: Long, immediate: Boolean) {
        val now = System.currentTimeMillis()
        val last = lastPushAt[nodeId]

        if (immediate || last == null || now - last >= PUSH_INTERVAL_MS) {
            pushNow(nodeId)

            return
        }

        // Held, not dropped: the timer pushes whatever the store says when it fires.
        if (pushPending.add(nodeId)) {
            vertx.setTimer((PUSH_INTERVAL_MS - (now - last)).coerceAtLeast(1)) {
                pushPending.remove(nodeId)

                pushNow(nodeId)
            }
        }
    }

    private fun pushNow(nodeId: Long) {
        lastPushAt[nodeId] = System.currentTimeMillis()

        // The nodes page's `node` frame -- or, for a Pano Agent, its server's `server` frame.
        panelRealtimeHub.notifyNodeUpdated(nodeId)

        if (!agentNodeDirectory.isAgent(nodeId)) {
            panelRealtimeHub.notifyNodeServersUpdated(nodeId)
        }
    }

    private fun ensureSweep() {
        synchronized(sweepLock) {
            if (sweepTimer < 0) {
                sweepTimer = vertx.setPeriodic(SWEEP_INTERVAL_MS) { sweep() }
            }
        }
    }

    private fun sweep() {
        store.sweep(System.currentTimeMillis()).forEach { nodeId ->
            pushNow(nodeId)

            // Gone for good (DONE or FAILED shown long enough): nothing left to pace.
            if (store.get(nodeId) == null) {
                lastPushAt.remove(nodeId)
            }
        }

        synchronized(sweepLock) {
            if (sweepTimer >= 0 && store.isEmpty()) {
                vertx.cancelTimer(sweepTimer)

                sweepTimer = -1
            }
        }
    }

    companion object {
        /** At most one push per node this often for a percentage; a state change never waits. */
        const val PUSH_INTERVAL_MS = 500L

        /** How often the store's clock is applied while it holds anything. */
        const val SWEEP_INTERVAL_MS = 2_000L
    }
}
