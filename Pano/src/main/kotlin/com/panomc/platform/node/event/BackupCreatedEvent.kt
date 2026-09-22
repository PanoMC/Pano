package com.panomc.platform.node.event

import com.panomc.platform.annotation.Event
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.Node
import com.panomc.platform.node.NodeEvent
import com.panomc.platform.node.NodeEventResponse
import com.panomc.platform.node.NodeManager
import com.panomc.platform.node.event.request.BackupCreatedEventRequest
import com.panomc.platform.server.backup.ManagedServerBackupService
import com.panomc.platform.server.feature.ServerFeatureSource

/**
 * A backup a node finished (`BACKUP_CREATED`).
 *
 * The size and checksum only exist once the archive does, which is why they arrive here rather
 * than being written when the backup was requested. This is also where retention runs: a new
 * backup is the only moment the count of them can have gone over the limit.
 */
@Event
class BackupCreatedEvent(
    private val databaseManager: DatabaseManager,
    private val nodeManager: NodeManager,
    private val managedServerBackupService: ManagedServerBackupService
) : NodeEvent<BackupCreatedEventRequest, NodeEventResponse>() {
    override suspend fun handle(request: BackupCreatedEventRequest, node: Node): NodeEventResponse? {
        val sqlClient = databaseManager.getSqlClient()

        // resolveServer is the choke point that stops a node speaking about another node's server.
        val server = nodeManager.resolveServer(node, request.serverUuid, sqlClient) ?: return null

        val backup = request.backup ?: return null
        val backupId = backup.id ?: return null

        managedServerBackupService.onCreated(
            server = server,
            report = ManagedServerBackupService.CreatedReport.of(
                source = ServerFeatureSource.NODE,
                backupId = backupId,
                name = backup.name,
                sizeBytes = backup.sizeBytes,
                sha256 = backup.sha256,
                createdAt = backup.createdAt,
                mode = backup.mode,
                scope = backup.scope,
                fileCount = backup.fileCount,
                storedBytes = backup.storedBytes,
                include = backup.include,
                exclude = backup.exclude
            ),
            sqlClient = sqlClient
        )

        return null
    }
}
