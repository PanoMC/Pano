package com.panomc.platform.node

import com.panomc.platform.Main
import com.panomc.platform.auth.panel.log.AutoUpdatedNodeLog
import com.panomc.platform.config.ConfigManager
import com.panomc.platform.config.PanoConfig
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.Node
import com.panomc.platform.node.message.SelfUpdateMessage
import io.vertx.core.Vertx
import io.vertx.kotlin.coroutines.dispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.slf4j.Logger
import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.context.annotation.Lazy
import org.springframework.context.annotation.Scope
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap

/**
 * Hands a node the daemon this Pano serves (`SELF_UPDATE`), by hand or on its own.
 *
 * [update] is the one path both take: `POST /api/panel/nodes/:id/update`, the agent update of a
 * server, and the automatic update that follows a hello all end up sending the same message with
 * the same relative URL and the same checksum, so there is exactly one way a node is ever told to
 * replace itself.
 *
 * The automatic half ([onHello]) runs when `managed-servers.node-auto-update` is on (the default)
 * and [NodeUpdateAvailability] says the node runs something older than what Pano serves. A hello
 * follows every connect, so this also covers Pano itself starting with a newer jar: every node
 * reconnects and says hello. It is careful in three ways:
 *
 * - never twice for the same jar within thirty minutes ([NodeAutoUpdateThrottle]), so an update that
 *   fails cannot turn into a node restarting in a loop;
 * - never while the node has a task running -- an install, an import, a backup -- because the
 *   daemon restarts to finish the update and would take that task with it. It looks again every
 *   [BUSY_RETRY_MILLIS] for as long as the node stays connected;
 * - recorded as `AUTO_UPDATED_NODE` in the activity log with no user behind it: nobody pressed
 *   anything, Pano did it, and "why did my node restart at 4 a.m." deserves an answer.
 */
@Lazy
@Component
@Scope(value = ConfigurableBeanFactory.SCOPE_SINGLETON)
class NodeDaemonUpdateService(
    private val vertx: Vertx,
    private val databaseManager: DatabaseManager,
    private val nodeManager: NodeManager,
    private val nodeJarProvider: NodeJarProvider,
    private val configManager: ConfigManager,
    private val nodeUpdateProgressService: NodeUpdateProgressService,
    private val logger: Logger
) {
    /** How a request to update one node ended. */
    sealed class Outcome {
        /** This Pano has no daemon jar on disk to hand out. */
        object NoJar : Outcome()

        /** The node already runs exactly these bytes. */
        data class UpToDate(val sha256: String) : Outcome()

        /** The node is not connected, so nothing was sent. */
        object Offline : Outcome()

        /** `SELF_UPDATE` went out; the node reports the rest as a `SELF_UPDATE` task. */
        data class Sent(val version: String, val sha256: String) : Outcome()
    }

    private val throttle = NodeAutoUpdateThrottle()

    /** Nodes waiting for a busy task to end before an automatic update, by id, to their timer. */
    private val pendingRetries = ConcurrentHashMap<Long, Long>()

    /** Whether automatic node updates are on (`managed-servers.node-auto-update`, default true). */
    val autoUpdateEnabled: Boolean
        get() = configManager.config.effectiveManagedServers.nodeAutoUpdate

    /**
     * Switches automatic node updates on or off and saves config.conf. A `managed-servers` block
     * that was hand-removed is written back, since that is where the key lives.
     */
    fun setAutoUpdateEnabled(enabled: Boolean) {
        val managedServers = configManager.config.managedServers
            ?: PanoConfig.Companion.ManagedServersConfig().also { configManager.config.managedServers = it }

        managedServers.nodeAutoUpdate = enabled

        configManager.saveConfig()
    }

    /**
     * Sends `SELF_UPDATE` to [nodeId] with the jar this Pano serves, unless it already runs it.
     *
     * The URL is relative. Pano does not know which address a node reaches it on -- a tunnel, a LAN
     * address, the public hostname -- and the node resolves it against the address it is already
     * connected over. [SelfUpdateMessage.sha256] is what makes that safe on a plain-HTTP network:
     * the node verifies the bytes before swapping anything, then exits 75 to be restarted.
     */
    suspend fun update(nodeId: Long): Outcome {
        val jar = nodeJarProvider.locate() ?: return Outcome.NoJar

        val sha256 = nodeJarProvider.sha256(jar)

        // Already running these exact bytes: sending SELF_UPDATE anyway would download the jar the
        // daemon is executing from, stage it over itself and restart the node for nothing.
        if (NodeUpdateAvailability.isSameJar(nodeManager.getJarSha256(nodeId), sha256)) {
            return Outcome.UpToDate(sha256)
        }

        val sent = nodeManager.sendMessage(
            nodeId,
            SelfUpdateMessage(
                version = Main.VERSION,
                url = "/api/node/${LocalNodeJarLocator.JAR_NAME}",
                sha256 = sha256
            )
        )

        if (!sent) {
            return Outcome.Offline
        }

        // Every way a node is updated passes here, so this is where its progress starts (SM-77):
        // RUNNING 0 % on the nodes page and on every server of the node until its hello says done.
        nodeUpdateProgressService.onStarted(nodeId, nodeManager.getConnectedNodeById(nodeId), Main.VERSION, sha256)

        return Outcome.Sent(Main.VERSION, sha256)
    }

    /**
     * Whether [node] runs an older daemon than this Pano serves, by the same rule the nodes page
     * shows its "update available" with. False when Pano has no jar to serve.
     */
    suspend fun isUpdateAvailable(node: Node): Boolean {
        val served = nodeJarProvider.locate()?.let { nodeJarProvider.sha256(it) }

        return NodeUpdateAvailability.isAvailable(node.version, Main.VERSION, nodeManager.getJarSha256(node.id), served)
    }

    /**
     * Called at the end of every `NODE_HELLO`: starts the automatic update of [node] if it is due.
     * Fire and forget -- the hello never waits on a checksum or a database query for this.
     */
    fun onHello(node: Node) {
        pendingRetries.remove(node.id)?.let { vertx.cancelTimer(it) }

        CoroutineScope(vertx.dispatcher()).launch {
            try {
                autoUpdate(node.id)
            } catch (e: Exception) {
                logger.warn("Automatic update check of node ${node.id} failed: ${e.message}")
            }
        }
    }

    /** Forgets everything kept about [nodeId]; for a node that was deleted. */
    fun forget(nodeId: Long) {
        pendingRetries.remove(nodeId)?.let { vertx.cancelTimer(it) }

        throttle.forget(nodeId)
    }

    private suspend fun autoUpdate(nodeId: Long) {
        if (!autoUpdateEnabled) {
            return
        }

        // The connected object carries what this hello just reported (version, protocol).
        val node = nodeManager.getConnectedNodeById(nodeId) ?: return

        val jar = nodeJarProvider.locate() ?: return
        val served = nodeJarProvider.sha256(jar)

        val available = NodeUpdateAvailability.isAvailable(node.version, Main.VERSION, nodeManager.getJarSha256(nodeId), served)

        if (!available) {
            return
        }

        val sqlClient = databaseManager.getSqlClient()

        val busy = databaseManager.serverTaskDao.getAllUnfinished(sqlClient).any { it.nodeId == nodeId }

        if (busy) {
            scheduleRetry(nodeId)

            return
        }

        if (!throttle.tryAcquire(nodeId, served)) {
            return
        }

        val fromVersion = node.version

        when (val outcome = update(nodeId)) {
            is Outcome.Sent -> {
                logger.info(
                    "Updating node \"${node.name}\" (${node.id}) automatically from ${fromVersion ?: "unknown"} to " +
                        "${outcome.version} (${outcome.sha256.take(12)})."
                )

                databaseManager.panelActivityLogDao.add(
                    AutoUpdatedNodeLog(node.id, node.name, fromVersion, outcome.version, outcome.sha256, node.agent),
                    sqlClient
                )
            }

            else -> Unit
        }
    }

    private fun scheduleRetry(nodeId: Long) {
        val timer = vertx.setTimer(BUSY_RETRY_MILLIS) {
            pendingRetries.remove(nodeId)

            if (!nodeManager.isConnected(nodeId)) {
                return@setTimer
            }

            CoroutineScope(vertx.dispatcher()).launch {
                try {
                    autoUpdate(nodeId)
                } catch (e: Exception) {
                    logger.warn("Automatic update check of node $nodeId failed: ${e.message}")
                }
            }
        }

        pendingRetries.put(nodeId, timer)?.let { vertx.cancelTimer(it) }
    }

    companion object {
        /** How long a busy node is left alone before the automatic update looks again. */
        const val BUSY_RETRY_MILLIS = 5 * 60 * 1000L
    }
}
