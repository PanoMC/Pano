package com.panomc.platform.node

import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.Node
import com.panomc.platform.db.model.Server
import com.panomc.platform.db.model.ServerTask
import com.panomc.platform.error.BadRequest
import com.panomc.platform.error.NodeOffline
import com.panomc.platform.node.message.InstallServerMessage
import com.panomc.platform.node.message.InstallServerSpec
import com.panomc.platform.node.message.ReinstallKeepSpec
import com.panomc.platform.node.message.ReinstallServerMessage
import com.panomc.platform.panel.PanelRealtimeHub
import com.panomc.platform.server.ServerProcessState
import com.panomc.platform.server.software.ServerSoftwareCatalog
import io.vertx.sqlclient.SqlClient
import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.context.annotation.Lazy
import org.springframework.context.annotation.Scope
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Turns "install this server" into a task and a message on a node's socket.
 *
 * Shared by the create and reinstall endpoints, which differ only in whether the row already
 * existed. Resolving the download URL happens here, at the moment the install starts, rather than
 * being taken from whatever the wizard was shown: the two can be minutes apart and a build can be
 * superseded in between.
 */
@Lazy
@Component
@Scope(value = ConfigurableBeanFactory.SCOPE_SINGLETON)
class ManagedServerInstallService(
    private val databaseManager: DatabaseManager,
    private val nodeManager: NodeManager,
    private val serverSoftwareCatalog: ServerSoftwareCatalog,
    private val panelRealtimeHub: PanelRealtimeHub,
    private val managedPluginLinkService: ManagedPluginLinkService
) {
    /**
     * Starts an install of [server] on [node] and returns the task that tracks it.
     *
     * Throws [BadRequest] when the software or version cannot be resolved to a download, and
     * [NodeOffline] when the message could not be handed to the node — in both cases nothing has
     * been written, so the caller is free to fail the request outright.
     */
    suspend fun start(
        server: Server,
        node: Node,
        kind: ServerTaskKind,
        createdBy: Long,
        acceptEula: Boolean,
        /** Extra `server.properties` entries for this install, on top of the ones on the row. */
        properties: Map<String, String> = emptyMap(),
        sqlClient: SqlClient,
        /** What a reinstall carries over (SM-66); null keeps the node's old default, worlds only. */
        keep: ReinstallKeepSpec? = null,
        /**
         * A task that already exists and should carry this install instead of a new one: a
         * software change opens its REINSTALL task before it stops the server and takes the
         * backup, so the panel follows one task from the first step to the last (SM-66).
         */
        existingTask: ServerTask? = null
    ): ServerTask {
        val serverUuid = server.uuid ?: throw BadRequest()
        val software = server.software ?: throw BadRequest()
        val version = server.softwareVersion ?: throw BadRequest()

        if (!nodeManager.isConnected(node.id)) {
            throw NodeOffline()
        }

        val resolution = serverSoftwareCatalog.resolve(software, version) ?: throw BadRequest()

        // Issued before the message goes out and re-issued on every reinstall, so the credentials
        // written into the server directory are always the ones this install put there.
        val panoPlugin = managedPluginLinkService.buildSpec(server, node, sqlClient)

        val now = System.currentTimeMillis()

        val task = existingTask ?: ServerTask(
            uuid = UUID.randomUUID().toString(),
            serverId = server.id,
            nodeId = node.id,
            kind = kind,
            status = ServerTaskStatus.PENDING,
            percent = 0,
            // Says up front whether this server will come up linked, so a vanilla server that can
            // never have a plugin does not look like an install that quietly lost one.
            message = managedPluginLinkService.describe(server, panoPlugin),
            createdBy = createdBy,
            createdAt = now,
            updatedAt = now
        )

        val stored = if (existingTask != null) {
            existingTask
        } else {
            val taskId = databaseManager.serverTaskDao.add(task, sqlClient)

            databaseManager.serverTaskDao.getById(taskId, sqlClient) ?: task
        }

        val spec = InstallServerSpec(
            name = server.name,
            software = software,
            version = version,
            // Null is "automatic": the node picks the newest runtime this Minecraft
            // version runs on, which is a question only the node's host can answer.
            javaMajor = server.javaVersion,
            memoryMb = server.memoryMb ?: DEFAULT_MEMORY_MB,
            jvmArgs = server.jvmArgs,
            // Reserved on the row at creation time and sent explicitly. 0 -- "you pick
            // one" -- is still understood by the node, but Pano no longer says it: two
            // sides allocating out of two different pictures is what handed a new server
            // and a concurrent import the same port.
            port = server.gamePort ?: NODE_ALLOCATES_PORT,
            acceptEula = acceptEula,
            properties = server.properties + properties,
            downloadUrl = resolution.downloadUrl,
            installerUrl = resolution.installerUrl,
            md5 = resolution.md5,
            // Present only for Spigot, and the node's signal to compile rather than
            // download: a spec carries one or a download URL, never both.
            build = resolution.buildSpec,
            panoPlugin = panoPlugin,
            keep = keep.takeIf { kind == ServerTaskKind.REINSTALL }
        )

        // A reinstall has to go out under its own name. Sent as `INSTALL_SERVER`, which it was
        // until SM-66, the node installed straight into the server's directory and wiped it first
        // -- worlds included -- instead of installing beside it and carrying the worlds over.
        val message = if (kind == ServerTaskKind.REINSTALL) {
            ReinstallServerMessage(serverUuid = serverUuid, taskId = task.uuid, spec = spec)
        } else {
            InstallServerMessage(serverUuid = serverUuid, taskId = task.uuid, spec = spec)
        }

        val sent = nodeManager.sendMessage(node.id, message)

        if (!sent) {
            throw NodeOffline()
        }

        databaseManager.serverDao.updateProcessStateById(server.id, ServerProcessState.INSTALLING, null, sqlClient)

        panelRealtimeHub.pushServerState(server.id, ServerProcessState.INSTALLING.name, null, null, null)
        panelRealtimeHub.pushTaskProgress(stored)
        panelRealtimeHub.notifyServerUpdated(server.id)

        return stored
    }

    companion object {
        const val DEFAULT_MEMORY_MB = 2048

        /**
         * Port value that tells the node to allocate one from its own range.
         *
         * Kept for a node driven by hand and for a row created before ports were reserved on this
         * side; nothing Pano creates today leaves the choice open.
         */
        const val NODE_ALLOCATES_PORT = 0
    }
}
