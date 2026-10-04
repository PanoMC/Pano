package com.panomc.platform.node

import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.Node
import com.panomc.platform.db.model.ServerTask
import com.panomc.platform.error.FeatureUnavailable
import com.panomc.platform.error.NodeOffline
import com.panomc.platform.node.message.JavaInstallMessage
import com.panomc.platform.node.message.JavaRemoveMessage
import com.panomc.platform.panel.PanelRealtimeHub
import io.vertx.sqlclient.SqlClient
import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.context.annotation.Lazy
import org.springframework.context.annotation.Scope
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Opens the node-scoped tasks behind the Java card's buttons (SM-63, §2.4.28).
 *
 * Install and removal are the same four steps with a different message: refuse a node that cannot
 * do it, write the task row, push the message, and tell the panel the task exists. The row is
 * written before the push so a node that answers instantly — "already up to date" is one frame —
 * reports against a task Pano already has.
 */
@Lazy
@Component
@Scope(value = ConfigurableBeanFactory.SCOPE_SINGLETON)
class NodeJavaTaskService(
    private val databaseManager: DatabaseManager,
    private val nodeManager: NodeManager,
    private val panelRealtimeHub: PanelRealtimeHub
) {
    suspend fun install(node: Node, major: Int, createdBy: Long, sqlClient: SqlClient): ServerTask =
        open(node, ServerTaskKind.JAVA_INSTALL, "Installing Java $major", createdBy, sqlClient) { taskId ->
            JavaInstallMessage(taskId = taskId, major = major)
        }

    suspend fun remove(node: Node, major: Int, version: String?, createdBy: Long, sqlClient: SqlClient): ServerTask =
        open(
            node,
            ServerTaskKind.JAVA_REMOVE,
            if (version == null) "Removing Java $major" else "Removing Java $version",
            createdBy,
            sqlClient
        ) { taskId ->
            JavaRemoveMessage(taskId = taskId, major = major, version = version)
        }

    /**
     * Refuses [node] unless it is connected, approved and understands the Java messages.
     *
     * An old node would not fail the task, it would ignore it, and the panel would watch a PENDING
     * task for two minutes before the sweep called it a timeout — so the refusal comes first.
     */
    fun requireCapable(node: Node) {
        if (!node.approved || !nodeManager.isConnected(node.id)) {
            throw NodeOffline()
        }

        if (!node.resources.javaDownloads) {
            throw FeatureUnavailable(extras = mapOf("feature" to FEATURE_ID))
        }
    }

    private suspend fun open(
        node: Node,
        kind: ServerTaskKind,
        message: String,
        createdBy: Long,
        sqlClient: SqlClient,
        push: (String) -> NodeMessage
    ): ServerTask {
        requireCapable(node)

        val now = System.currentTimeMillis()

        val task = ServerTask(
            uuid = UUID.randomUUID().toString(),
            serverId = null,
            nodeId = node.id,
            kind = kind,
            status = ServerTaskStatus.PENDING,
            percent = 0,
            message = message,
            createdBy = createdBy,
            createdAt = now,
            updatedAt = now
        )

        val id = databaseManager.serverTaskDao.add(task, sqlClient)

        val stored = databaseManager.serverTaskDao.getById(id, sqlClient) ?: task

        if (!nodeManager.sendMessage(node.id, push(task.uuid))) {
            // Closed at once rather than left for the sweep: nothing will ever pick it up, and a
            // PENDING row would sit on the node's task list for two minutes saying otherwise.
            databaseManager.serverTaskDao.updateProgressByUuid(
                uuid = task.uuid,
                status = ServerTaskStatus.FAILED,
                percent = 0,
                message = message,
                error = NODE_OFFLINE_ERROR,
                updatedAt = System.currentTimeMillis(),
                sqlClient = sqlClient
            )

            throw NodeOffline()
        }

        panelRealtimeHub.pushTaskProgress(stored)

        return stored
    }

    companion object {
        /** The `feature` a refusal names, so the panel can say "update the node to download Java". */
        const val FEATURE_ID = "java.downloads"

        private const val NODE_OFFLINE_ERROR = "NODE_OFFLINE"
    }
}
