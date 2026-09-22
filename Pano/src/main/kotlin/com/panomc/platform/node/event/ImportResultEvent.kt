package com.panomc.platform.node.event

import com.panomc.platform.annotation.Event
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.Node
import com.panomc.platform.db.model.ServerTask
import com.panomc.platform.node.ImportStartHandoff
import com.panomc.platform.node.ManagedServerImportService
import com.panomc.platform.node.NodeEvent
import com.panomc.platform.node.NodeEventResponse
import com.panomc.platform.node.NodeManager
import com.panomc.platform.node.ServerTaskService
import com.panomc.platform.node.event.request.ImportResultEventRequest
import com.panomc.platform.panel.PanelRealtimeHub
import com.panomc.platform.server.ServerType
import io.vertx.sqlclient.SqlClient
import org.slf4j.Logger

/**
 * What an import turned out to be (`IMPORT_RESULT`).
 *
 * The row this updates was written before anybody knew what was being imported: the wizard's
 * software and version fields are optional for imports precisely because the answer lives in a
 * folder, a zip or a modpack that only the node can open. This is where the guess becomes a fact.
 *
 * Only what the node identified is written. A directory whose software could not be named is a
 * working managed server — the node can still start it, stream its console and back it up — so an
 * unknown software leaves the row's existing value alone rather than overwriting it with nothing.
 *
 * The plugin install follows here rather than being part of the import, because which Pano plugin
 * a server can take (or whether it can take one at all) is decided by the software that has only
 * just been discovered. When it goes out it takes the import's start with it
 * ([ImportStartHandoff]), and the whole frame is handled under the import task's lock so that the
 * import's DONE, which the node sends right after this frame, cannot decide whether to start the
 * server before this has decided who does.
 */
@Event
class ImportResultEvent(
    private val databaseManager: DatabaseManager,
    private val nodeManager: NodeManager,
    private val managedServerImportService: ManagedServerImportService,
    private val serverTaskService: ServerTaskService,
    private val panelRealtimeHub: PanelRealtimeHub,
    private val logger: Logger
) : NodeEvent<ImportResultEventRequest, NodeEventResponse>() {
    override suspend fun handle(request: ImportResultEventRequest, node: Node): NodeEventResponse? {
        val taskId = request.taskId

        if (taskId == null) {
            apply(request, node, null)

            return null
        }

        // Taken before anything here suspends. A node's frames start in the order they arrived and
        // this one precedes the import's DONE, so the DONE queues behind it on the same lock: by
        // the time it asks whether to start the server, the software is written and the plugin
        // install has either gone out carrying that start or will not go out at all.
        serverTaskService.withTaskLock(taskId) { apply(request, node, taskId) }

        return null
    }

    /**
     * Applies one result, and reports whether the import task can still receive another frame --
     * the lock's answer, exactly as `TaskProgressEvent` gives it.
     */
    private suspend fun apply(request: ImportResultEventRequest, node: Node, taskId: String?): Boolean {
        val sqlClient = databaseManager.getSqlClient()

        val task = taskId?.let { databaseManager.serverTaskDao.getByUuid(it, sqlClient) }

        // Nothing more arrives for a task that has no row or has already ended.
        val ended = task == null || task.status.isTerminal

        // The choke point that stops a node speaking about another node's server.
        val server = nodeManager.resolveServer(node, request.serverUuid, sqlClient) ?: return ended

        // A result for a task this node does not own is a result about somebody else's import.
        if (task != null && task.nodeId != node.id) {
            logger.warn("Node ${node.id} reported an import result for a task it does not own, ignoring.")

            return ended
        }

        val software = request.software?.trim()?.takeIf { it.isNotEmpty() }
        val version = request.version?.trim()?.takeIf { it.isNotEmpty() }

        val type = software?.let { typeOf(it) } ?: server.type

        databaseManager.serverDao.updateImportedSoftwareById(
            id = server.id,
            type = type,
            software = software ?: server.software,
            softwareVersion = version ?: server.softwareVersion,
            version = version ?: server.version,
            javaVersion = server.javaVersion ?: request.javaMajor,
            gamePort = request.port ?: server.gamePort,
            sqlClient = sqlClient
        )

        // The path the node actually resolved (symlinks followed) replaces the one the admin typed.
        // Only ever set here, never cleared: a node that says nothing has told Pano nothing.
        if (request.inPlace != null || request.directory != null) {
            databaseManager.serverDao.updateLocationById(
                id = server.id,
                inPlace = request.inPlace ?: server.inPlace,
                directory = request.directory?.take(MAX_DIRECTORY_LENGTH) ?: server.directory,
                sqlClient = sqlClient
            )
        }

        panelRealtimeHub.notifyServerUpdated(server.id)

        logger.info(
            "Imported server ${server.id} on node ${node.id} identified as " +
                "${software ?: "unknown software"} ${version.orEmpty()} (jar ${request.jar ?: "unknown"})."
        )

        linkPlugin(server.id, node, task, sqlClient)

        return ended
    }

    /**
     * Installs the Pano plugin into the freshly identified server, if it can take one, handing it
     * the import's start when [task]'s DONE is still to come ([ImportStartHandoff]).
     */
    private suspend fun linkPlugin(serverId: Long, node: Node, task: ServerTask?, sqlClient: SqlClient) {
        // Re-read, because the plugin spec is built from the software that was just written and
        // the row in hand still carries what it was created with.
        val updated = databaseManager.serverDao.getById(serverId, sqlClient) ?: return

        val startAfter = ImportStartHandoff.startAfterFor(
            task,
            task?.let { serverTaskService.startAfterOf(it.uuid) },
            updated.autoStart
        )

        try {
            val link = managedServerImportService.linkPlugin(
                updated,
                node,
                task?.createdBy ?: SYSTEM_USER,
                sqlClient,
                startAfter = startAfter
            )

            // Only an install that went out takes the start off the DONE. One that was never sent
            // -- no plugin for this software, no build to be had, a throw -- leaves it where it was.
            if (link != null && startAfter != null && task != null) {
                serverTaskService.carryStartInLink(task.uuid)
            }
        } catch (e: Exception) {
            // An import that worked must not be reported as failed because the link did not: the
            // server is there, and `/pano connect` is still a way to link it by hand.
            logger.warn("Could not link the Pano plugin into imported server $serverId: ${e.message}")
        }
    }

    /** The [ServerType] a detected software name maps to, or null when it is not one Pano knows. */
    private fun typeOf(software: String): ServerType? =
        ServerType.entries.firstOrNull { it.name.equals(software, ignoreCase = true) }

    companion object {
        /** Stands in when the task that started the import is already gone. */
        private const val SYSTEM_USER = -1L

        /** Longest directory path stored; anything longer is not a path somebody typed. */
        const val MAX_DIRECTORY_LENGTH = 4096
    }
}
