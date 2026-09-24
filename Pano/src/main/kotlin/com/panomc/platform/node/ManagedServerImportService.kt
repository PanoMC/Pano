package com.panomc.platform.node

import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.Node
import com.panomc.platform.db.model.Server
import com.panomc.platform.db.model.ServerTask
import com.panomc.platform.error.BadRequest
import com.panomc.platform.error.NodeOffline
import com.panomc.platform.node.message.ImportMode
import com.panomc.platform.node.message.ImportServerMessage
import com.panomc.platform.node.message.ImportServerSpec
import com.panomc.platform.node.message.InstallPanoPluginMessage
import com.panomc.platform.panel.PanelRealtimeHub
import com.panomc.platform.server.ServerActiveTaskStore
import com.panomc.platform.server.ServerProcessState
import io.vertx.sqlclient.SqlClient
import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.context.annotation.Lazy
import org.springframework.context.annotation.Scope
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Starts an import on a node and, later, links the server it produced.
 *
 * The twin of [ManagedServerInstallService], split in two because an import is two exchanges
 * rather than one. The first hands the node a source and a task; the second happens only once the
 * node has reported what it found, because until then Pano does not know which Pano-plugin build
 * the server can even take — a Paper server gets one, a Forge server gets none, and the row was
 * created before anybody could tell them apart.
 */
@Lazy
@Component
@Scope(value = ConfigurableBeanFactory.SCOPE_SINGLETON)
class ManagedServerImportService(
    private val databaseManager: DatabaseManager,
    private val nodeManager: NodeManager,
    private val panelRealtimeHub: PanelRealtimeHub,
    private val managedPluginLinkService: ManagedPluginLinkService,
    private val activeTaskStore: ServerActiveTaskStore
) {
    /**
     * Pushes `IMPORT_SERVER` for [server] and returns the task that tracks it.
     *
     * Throws [NodeOffline] when the message could not be handed over, so the caller can fail the
     * request rather than leave a row waiting on a node that is not there.
     */
    suspend fun start(
        server: Server,
        node: Node,
        mode: ImportMode,
        createdBy: Long,
        acceptEula: Boolean,
        folderPath: String? = null,
        ticket: String? = null,
        downloadUrl: String? = null,
        filename: String? = null,
        /**
         * The port to send instead of the row's: an `IN_PLACE` adoption the admin gave no port
         * sends [ManagedServerInstallService.NODE_ALLOCATES_PORT], so the server keeps the one in
         * its own `server.properties` and `IMPORT_RESULT` writes it back onto the row.
         */
        port: Int? = null,
        sqlClient: SqlClient
    ): ServerTask {
        val serverUuid = server.uuid ?: throw BadRequest()

        if (!nodeManager.isConnected(node.id)) {
            throw NodeOffline()
        }

        val now = System.currentTimeMillis()

        val task = ServerTask(
            uuid = UUID.randomUUID().toString(),
            serverId = server.id,
            nodeId = node.id,
            kind = ServerTaskKind.IMPORT,
            status = ServerTaskStatus.PENDING,
            percent = 0,
            message = describe(mode),
            createdBy = createdBy,
            createdAt = now,
            updatedAt = now
        )

        val taskId = databaseManager.serverTaskDao.add(task, sqlClient)

        val stored = databaseManager.serverTaskDao.getById(taskId, sqlClient) ?: task

        val sent = nodeManager.sendMessage(
            node.id,
            ImportServerMessage(
                serverUuid = serverUuid,
                taskId = task.uuid,
                mode = mode.name,
                folderPath = folderPath,
                ticket = ticket,
                downloadUrl = downloadUrl,
                filename = filename,
                spec = ImportServerSpec(
                    name = server.name,
                    memoryMb = server.memoryMb ?: ManagedServerInstallService.DEFAULT_MEMORY_MB,
                    // The port Pano reserved on the row when the server was created. It is sent
                    // rather than left at 0 because both sides allocating is what put two servers
                    // on 25567; the cost is that an imported server is moved to Pano's number
                    // instead of keeping the one in its own server.properties, which is the side
                    // of the trade that can be seen and changed in the startup settings.
                    port = port ?: server.gamePort ?: ManagedServerInstallService.NODE_ALLOCATES_PORT,
                    javaMajor = server.javaVersion,
                    jvmArgs = server.jvmArgs,
                    acceptEula = acceptEula,
                    autoStart = if (mode == ImportMode.IN_PLACE) server.autoStart else null,
                    // Only a server Pano builds has any (see PanelCreateServerAPI.initialProperties).
                    properties = server.properties.takeIf { it.isNotEmpty() }
                )
            )
        )

        if (!sent) {
            throw NodeOffline()
        }

        databaseManager.serverDao.updateProcessStateById(server.id, ServerProcessState.INSTALLING, null, sqlClient)

        panelRealtimeHub.pushServerState(server.id, ServerProcessState.INSTALLING.name, null, null, null)
        panelRealtimeHub.pushTaskProgress(stored)
        panelRealtimeHub.notifyServerUpdated(server.id)

        return stored
    }

    /**
     * Installs the Pano plugin into a server that has just been imported.
     *
     * Best effort by design, and reported as its own task: a Forge server has no Pano plugin and
     * is a perfectly good managed server without one, so a null spec is silence rather than a
     * failure. What must not happen is an import that succeeds and a link that fails invisibly,
     * which is why the case that *can* work gets a task somebody can see.
     *
     * [panoPluginUpdate] is for `PanoPluginUpdateService`, whose update of a server with no Pano
     * jar yet is this first link: the task is then marked as a Pano plugin update (SM-77).
     *
     * [startAfter] is an import's start riding on this install ([ImportStartHandoff]): the node
     * starts the server once the install has ended. Null leaves the start to somebody else.
     */
    suspend fun linkPlugin(
        server: Server,
        node: Node,
        createdBy: Long,
        sqlClient: SqlClient,
        panoPluginUpdate: Boolean = false,
        startAfter: Boolean? = null
    ): ServerTask? {
        val serverUuid = server.uuid ?: return null

        val spec = managedPluginLinkService.buildSpec(server, node, sqlClient) ?: return null

        val now = System.currentTimeMillis()

        val task = ServerTask(
            uuid = UUID.randomUUID().toString(),
            serverId = server.id,
            nodeId = node.id,
            kind = ServerTaskKind.PLUGIN_INSTALL,
            status = ServerTaskStatus.PENDING,
            percent = 0,
            message = managedPluginLinkService.describe(server, spec),
            createdBy = createdBy,
            createdAt = now,
            updatedAt = now
        )

        val taskId = databaseManager.serverTaskDao.add(task, sqlClient)

        val stored = databaseManager.serverTaskDao.getById(taskId, sqlClient) ?: task

        // Before the first push, so the task says what it is from its first frame (SM-77).
        if (panoPluginUpdate) {
            activeTaskStore.markPanoPluginUpdate(stored.uuid)
        }

        // Pushed before the message goes out: a node that answers at once must not have its
        // RUNNING frame overwritten by this PENDING one in the server's `activeTask`.
        panelRealtimeHub.pushTaskProgress(stored)

        nodeManager.sendMessage(node.id, InstallPanoPluginMessage(serverUuid, task.uuid, spec, startAfter))

        return stored
    }

    private fun describe(mode: ImportMode): String = when (mode) {
        ImportMode.FOLDER -> "Copying the server folder"
        ImportMode.UPLOAD -> "Unpacking the uploaded archive"
        ImportMode.MODPACK -> "Building the modpack"
        ImportMode.IN_PLACE -> "Linking the server where it is"
    }
}
