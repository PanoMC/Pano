package com.panomc.platform.server.plugins

import com.panomc.platform.auth.panel.log.ServerPanoPluginUpdatedLog
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.Server
import com.panomc.platform.db.model.ServerTask
import com.panomc.platform.node.ManagedPluginJarResolver
import com.panomc.platform.node.ManagedServerImportService
import com.panomc.platform.node.NodeManager
import com.panomc.platform.node.ServerTaskKind
import com.panomc.platform.node.ServerTaskStatus
import com.panomc.platform.node.dto.ScannedPluginData
import com.panomc.platform.node.message.InstallPluginMessage
import com.panomc.platform.node.message.PluginScanMessage
import com.panomc.platform.panel.PanelRealtimeHub
import com.panomc.platform.server.ServerActiveTaskStore
import com.panomc.platform.server.ServerCapability
import com.panomc.platform.server.ServerManager
import com.panomc.platform.server.message.PanoPluginUpdateMessage
import com.panomc.platform.server.plugins.PanoPluginUpdatePlan.Mode
import io.vertx.sqlclient.SqlClient
import org.slf4j.Logger
import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.context.annotation.Lazy
import org.springframework.context.annotation.Scope
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Starts an update of the Pano plugin on one server, by whichever route that server can take.
 *
 * [PanoPluginUpdatePlan] decides the route; this is where it is carried out, and it is shared by
 * the single-server endpoint and "update all" so the two can never disagree about what an update
 * is. Every attempt either starts exactly one `PLUGIN_INSTALL` task — created before anything is
 * sent, so a first progress frame always finds its row — or is refused with one of the plan's
 * reason strings and leaves nothing behind.
 *
 * The node route sends `INSTALL_PLUGIN` rather than `INSTALL_PANO_PLUGIN` whenever there is a Pano
 * jar to replace, which is a deliberate difference from a first install. `INSTALL_PANO_PLUGIN` is a
 * link: it reissues the server's token (invalidating the one the running plugin holds, so a socket
 * drop before the restart could not reconnect), rewrites `config.conf`, verifies nothing and leaves
 * the old jar where it is — and with versioned release names that means two copies of the plugin
 * on the next boot. `INSTALL_PLUGIN` checks the SHA-256 Pano computed, moves the jar into place
 * atomically and deletes exactly the jar it supersedes once the new one is there. Only a managed
 * server with no Pano jar at all gets `INSTALL_PANO_PLUGIN`, because for it this is a first link.
 *
 * Whichever route, the task is marked in [ServerActiveTaskStore] as a Pano plugin update before its
 * first push (SM-77), so the server JSON's `activeTask` -- and every `taskProgress` frame -- carries
 * `panoPluginUpdate: true` and the header can say what is happening. That first push goes out
 * before the message does: a node or plugin quick enough to answer ahead of a push made after the
 * send would have its RUNNING frame overwritten in the store by the PENDING one.
 */
@Lazy
@Component
@Scope(value = ConfigurableBeanFactory.SCOPE_SINGLETON)
class PanoPluginUpdateService(
    private val databaseManager: DatabaseManager,
    private val nodeManager: NodeManager,
    private val serverManager: ServerManager,
    private val panelRealtimeHub: PanelRealtimeHub,
    private val panoPluginJarProvider: PanoPluginJarProvider,
    private val managedServerImportService: ManagedServerImportService,
    private val activeTaskStore: ServerActiveTaskStore,
    private val logger: Logger
) {
    /** What one attempt came to. */
    sealed interface Attempt {
        data class Started(
            val task: ServerTask,
            val mode: Mode,
            val fromVersion: String?,
            val toVersion: String?
        ) : Attempt

        /** Nothing was started; [reason] is one of the `PanoPluginUpdatePlan.REASON_*` strings. */
        data class Refused(val reason: String) : Attempt
    }

    /** The route an update of [server] would take right now, or null when it has none. */
    fun modeFor(server: Server): Mode? = PanoPluginUpdatePlan.modeFor(
        managed = server.isManaged && server.nodeId != null && server.uuid != null,
        nodeConnected = server.nodeId?.let { nodeManager.isConnected(it) } == true,
        pluginConnected = serverManager.isConnected(server.id),
        pluginCanSelfUpdate = server.hasCapability(ServerCapability.SELF_UPDATE)
    )

    /**
     * Whether [server] has no route right now but its admin can update it by hand from the jar
     * `GET /api/panel/servers/:id/pano-plugin/jar` serves (see [PanoPluginUpdatePlan.canUpdateByHand]).
     */
    fun canUpdateByHand(server: Server): Boolean {
        if (ManagedPluginJarResolver.platformOf(server.type) == null || modeFor(server) != null) {
            return false
        }

        val reason = PanoPluginUpdatePlan.refusalFor(
            type = server.type,
            managed = server.isManaged,
            pluginConnected = serverManager.isConnected(server.id)
        )

        return PanoPluginUpdatePlan.canUpdateByHand(reason, server.isManaged)
    }

    /**
     * Updates the Pano plugin on [server] on behalf of [userId], and writes the audit entry.
     *
     * Refuses an update to the version that is already running only when both versions are real
     * releases and equal; a development jar has no version to compare, and there the admin pressing
     * the button is the only signal there is.
     */
    suspend fun start(server: Server, userId: Long, sqlClient: SqlClient): Attempt {
        val platform = ManagedPluginJarResolver.platformOf(server.type)
            ?: return Attempt.Refused(PanoPluginUpdatePlan.REASON_NO_PLUGIN_MODULE)

        val mode = modeFor(server) ?: return Attempt.Refused(
            PanoPluginUpdatePlan.refusalFor(
                type = server.type,
                managed = server.isManaged,
                pluginConnected = serverManager.isConnected(server.id)
            )
        )

        val latest = panoPluginJarProvider.latestVersion(server.type)

        if (PanoPluginStatus.updateAvailable(server.pluginVersion, latest) == false) {
            return Attempt.Refused(PanoPluginUpdatePlan.REASON_UP_TO_DATE)
        }

        val attempt = when (mode) {
            Mode.NODE -> startOnNode(server, platform, userId, sqlClient)
            Mode.PLUGIN -> startOnPlugin(server, userId, sqlClient)
        }

        if (attempt is Attempt.Started) {
            record(server, userId, attempt, sqlClient)
        }

        return attempt
    }

    /**
     * The node route: find the jar being replaced, then `INSTALL_PLUGIN` the new one over it.
     *
     * The scan is what makes the replacement exact. Guessing the old file from the version on the
     * row would be wrong for a jar somebody renamed and for every development build, and sending
     * nothing to replace would leave two Pano jars in the directory.
     */
    private suspend fun startOnNode(server: Server, platform: String, userId: Long, sqlClient: SqlClient): Attempt {
        val nodeId = server.nodeId ?: return Attempt.Refused(PanoPluginUpdatePlan.REASON_NODE_OFFLINE)
        val serverUuid = server.uuid ?: return Attempt.Refused(PanoPluginUpdatePlan.REASON_NODE_OFFLINE)

        val scan = try {
            nodeManager.request(nodeId, PluginScanMessage(serverUuid), SCAN_TIMEOUT_MS)
        } catch (e: Exception) {
            logger.warn("Could not scan server ${server.id}'s plugins before updating Pano: ${e.message}")

            return Attempt.Refused(PanoPluginUpdatePlan.REASON_SCAN_FAILED)
        }

        if (!scan.getBoolean("ok", false)) {
            return Attempt.Refused(PanoPluginUpdatePlan.REASON_SCAN_FAILED)
        }

        val existing = PanoPluginUpdatePlan.panoJarIn(ScannedPluginData.listFrom(scan), platform)

        if (existing == null) {
            // Nothing to replace: this server was never linked, so the update is a first link and
            // gets the credentials a first link needs.
            val node = nodeManager.getConnectedNodeById(nodeId)
                ?: return Attempt.Refused(PanoPluginUpdatePlan.REASON_NODE_OFFLINE)

            val task = managedServerImportService.linkPlugin(server, node, userId, sqlClient, panoPluginUpdate = true)
                ?: return Attempt.Refused(PanoPluginUpdatePlan.REASON_JAR_UNAVAILABLE)

            return Attempt.Started(task, Mode.NODE, server.pluginVersion, panoPluginJarProvider.latestVersion(server.type))
        }

        val jar = panoPluginJarProvider.prepare(server.type)
            ?: return Attempt.Refused(PanoPluginUpdatePlan.REASON_JAR_UNAVAILABLE)

        val task = openTask(server, nodeId, userId, jar, sqlClient)

        panelRealtimeHub.pushTaskProgress(task)

        val sent = nodeManager.sendMessage(
            nodeId,
            InstallPluginMessage(
                serverUuid = serverUuid,
                taskId = task.uuid,
                downloadUrl = jar.nodeUrl,
                filename = jar.fileName,
                targetDir = ManagedPluginJarResolver.targetDirOf(server.type),
                sha256 = jar.sha256,
                // Last on the node's side and only on success, so a failed download leaves the
                // running jar exactly where it was.
                replaceFilename = existing
            )
        )

        if (!sent) {
            failUnsent(task, sqlClient)

            return Attempt.Refused(PanoPluginUpdatePlan.REASON_SEND_FAILED)
        }

        return Attempt.Started(task, Mode.NODE, server.pluginVersion, jar.version)
    }

    /**
     * The plugin route: `PANO_PLUGIN_UPDATE` with everything the plugin needs to check the bytes.
     *
     * The URL is Pano's own endpoint, never the release asset: the plugin authenticates with the
     * token it already holds, and the checksum it verifies is of the very file that endpoint serves.
     */
    private suspend fun startOnPlugin(server: Server, userId: Long, sqlClient: SqlClient): Attempt {
        val jar = panoPluginJarProvider.prepare(server.type)
            ?: return Attempt.Refused(PanoPluginUpdatePlan.REASON_JAR_UNAVAILABLE)

        val task = openTask(server, null, userId, jar, sqlClient)

        panelRealtimeHub.pushTaskProgress(task)

        val sent = serverManager.sendMessage(
            server.id,
            PanoPluginUpdateMessage(
                eventId = UUID.randomUUID().toString(),
                taskId = task.uuid,
                url = SERVER_JAR_PATH,
                sha256 = jar.sha256,
                size = jar.size,
                fileName = jar.fileName,
                version = jar.version
            )
        )

        if (!sent) {
            failUnsent(task, sqlClient)

            return Attempt.Refused(PanoPluginUpdatePlan.REASON_SEND_FAILED)
        }

        return Attempt.Started(task, Mode.PLUGIN, server.pluginVersion, jar.version)
    }

    private suspend fun openTask(
        server: Server,
        nodeId: Long?,
        userId: Long,
        jar: PanoPluginJarProvider.PreparedJar,
        sqlClient: SqlClient
    ): ServerTask {
        val now = System.currentTimeMillis()

        val task = ServerTask(
            uuid = UUID.randomUUID().toString(),
            serverId = server.id,
            nodeId = nodeId,
            kind = ServerTaskKind.PLUGIN_INSTALL,
            status = ServerTaskStatus.PENDING,
            percent = 0,
            message = "${ServerActiveTaskStore.PANO_PLUGIN_UPDATE_MESSAGE_PREFIX} to ${jar.version ?: jar.fileName}",
            createdBy = userId,
            createdAt = now,
            updatedAt = now
        )

        val id = databaseManager.serverTaskDao.add(task, sqlClient)

        activeTaskStore.markPanoPluginUpdate(task.uuid)

        return databaseManager.serverTaskDao.getById(id, sqlClient) ?: task
    }

    /**
     * Ends a task whose push never left Pano, instead of leaving it for the timeout sweep.
     *
     * The sweep would get there in two minutes, but "update all" can open a dozen of these in one
     * click, and a panel full of spinners for work nobody was ever sent is worse than an honest
     * failure right away.
     */
    private suspend fun failUnsent(task: ServerTask, sqlClient: SqlClient) {
        task.status = ServerTaskStatus.FAILED
        task.error = PanoPluginUpdatePlan.REASON_SEND_FAILED
        task.updatedAt = System.currentTimeMillis()

        try {
            databaseManager.serverTaskDao.updateProgressByUuid(
                uuid = task.uuid,
                status = task.status,
                percent = task.percent,
                message = task.message,
                error = task.error,
                updatedAt = task.updatedAt,
                sqlClient = sqlClient
            )
        } catch (e: Exception) {
            logger.warn("Could not fail the unsent Pano plugin update task ${task.uuid}: ${e.message}")
        }

        panelRealtimeHub.pushTaskProgress(task)
    }

    private suspend fun record(server: Server, userId: Long, started: Attempt.Started, sqlClient: SqlClient) {
        try {
            val username = databaseManager.userDao.getUsernameFromUserId(userId, sqlClient) ?: return

            databaseManager.panelActivityLogDao.add(
                ServerPanoPluginUpdatedLog(
                    userId = userId,
                    username = username,
                    serverId = server.id,
                    serverName = server.customName ?: server.name,
                    fromVersion = started.fromVersion,
                    toVersion = started.toVersion,
                    mode = started.mode.wire
                ),
                sqlClient
            )
        } catch (e: Exception) {
            // The update is already on its way; an audit row that would not store is a log line,
            // not a reason to tell the admin it failed.
            logger.warn("Could not record the Pano plugin update on server ${server.id}: ${e.message}")
        }
    }

    companion object {
        /** Where a plugin fetches its successor from; see `ServerPanoPluginJarAPI`. */
        const val SERVER_JAR_PATH = "/api/server/pano-plugin/jar"

        /** How long the node gets to list the plugin directory before the update is refused. */
        private const val SCAN_TIMEOUT_MS = 10_000L
    }
}
