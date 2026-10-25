package com.panomc.platform.backup

import com.panomc.platform.backup.remote.McServerBackupImporter
import com.panomc.platform.backup.remote.McServerBackupSource
import com.panomc.platform.db.model.ServerBackup
import com.panomc.platform.node.message.BackupImportMessage
import com.panomc.platform.panel.PanelRealtimeHub
import com.panomc.platform.server.backup.BackupScope
import com.panomc.platform.server.feature.ServerFeature
import com.panomc.platform.server.feature.ServerFeatureSource
import java.util.UUID
import com.panomc.platform.backup.remote.PanoHostClient
import com.panomc.platform.backup.remote.PanoHostException
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.error.NotExists
import com.panomc.platform.node.ManagedServerFileClient
import com.panomc.platform.node.message.TransferPullMessage
import com.panomc.platform.node.transfer.TransferDirection
import com.panomc.platform.node.transfer.TransferTicketStore
import com.panomc.platform.route.api.panel.server.backups.PanelDownloadServerBackupAPI
import com.panomc.platform.server.backup.BackupMode
import com.panomc.platform.server.backup.ServerBackupStatus
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.sqlclient.SqlClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.context.annotation.Lazy
import org.springframework.context.annotation.Scope
import org.springframework.stereotype.Component

/**
 * Turns a managed MC server backup (pano-node or agent-lite) into a [McServerBackupSource]: the zip
 * (a snapshot is streamed as one) is pulled through the same transfer-ticket path a browser download
 * uses, into a file Pano keeps, and checked against the sha256 the source reported (a full zip's).
 * The way back is [importer]: a zip out of Pano Backup is pushed to a server's node as a new backup of it.
 */
@Lazy
@Component
@Scope(value = ConfigurableBeanFactory.SCOPE_SINGLETON)
class McServerBackupSources(
    private val databaseManager: DatabaseManager,
    private val fileClient: ManagedServerFileClient,
    private val transferTicketStore: TransferTicketStore,
    private val panelRealtimeHub: PanelRealtimeHub
) {
    suspend fun source(serverId: Long, backupId: String, sqlClient: SqlClient): McServerBackupSource {
        val backup = databaseManager.serverBackupDao.getByUuid(backupId, sqlClient) ?: throw NotExists()

        if (backup.serverId != serverId || backup.status != ServerBackupStatus.READY) {
            throw NotExists()
        }

        val server = databaseManager.serverDao.getById(serverId, sqlClient) ?: throw NotExists()

        val meta = JsonObject()
            .put("id", backup.uuid)
            .put("name", backup.name)
            .put("serverName", server.name)
            .put("sizeBytes", backup.sizeBytes)
            .put("sha256", backup.sha256)
            .put("createdAt", backup.createdAt)
            .put("mode", backup.mode.name)
            .put("scope", backup.scope.name)
            .put("fileCount", backup.fileCount)
            .put("include", JsonArray(backup.include))
            .put("exclude", JsonArray(backup.exclude))

        return McServerBackupSource(
            serverId = serverId,
            subject = server.uuid ?: "server-$serverId",
            backupId = backup.uuid,
            meta = meta,
            settings = server.settings.panoBackup
        ) { file ->
            val target = fileClient.resolve(serverId, databaseManager.getSqlClient())
            val path = PanelDownloadServerBackupAPI.VIRTUAL_PREFIX + backup.uuid

            val ticket = transferTicketStore.issue(
                userId = backup.createdBy,
                serverId = serverId,
                nodeId = target.nodeId,
                serverUuid = target.serverUuid,
                path = path,
                direction = TransferDirection.DOWNLOAD,
                fileName = "${backup.name}.zip",
                ttlMs = TICKET_TTL_MS,
                downloadFile = file
            )

            try {
                fileClient.send(target, TransferPullMessage(ticket.id, target.serverUuid, path))

                withTimeoutOrNull(FETCH_TIMEOUT_MS) { ticket.completion.await() }
                    ?: throw PanoHostException(FETCH_FAILED, message = "The server did not send the backup in time.")
            } catch (e: PanoHostException) {
                throw e
            } catch (e: Exception) {
                throw PanoHostException(FETCH_FAILED, message = e.message, cause = e)
            } finally {
                transferTicketStore.discard(ticket)
            }

            val expected = backup.sha256

            if (expected != null && !withContext(Dispatchers.IO) { PanoHostClient.sha256(file) }.equals(expected, ignoreCase = true)) {
                throw PanoHostException(PanoHostException.INTEGRITY_FAILED, message = "The server backup does not match its checksum.")
            }
        }
    }

    /**
     * An importer acting for [userId]: the zip is pushed to [McServerBackupImporter.import]'s server through an upload
     * ticket ([BackupImportMessage]), and a READY row is written once the node has stored it. Only a node of protocol
     * [MIN_IMPORT_PROTOCOL] or newer can take one; a server served by its plugin cannot (`IMPORT_UNSUPPORTED`).
     */
    fun importer(userId: Long) = McServerBackupImporter { serverId, zip, meta, onProgress ->
        val sqlClient = databaseManager.getSqlClient()
        val target = fileClient.resolve(serverId, sqlClient, ServerFeature.BACKUPS_CREATE)
        val nodeId = target.nodeId

        if (target.source != ServerFeatureSource.NODE || nodeId == null) {
            throw PanoHostException(IMPORT_UNSUPPORTED, message = "This server's backups are not kept by a node.")
        }

        val node = databaseManager.nodeDao.getById(nodeId, sqlClient)

        if (node == null || node.protocolVersion < MIN_IMPORT_PROTOCOL) {
            throw PanoHostException(NODE_TOO_OLD, message = "Update this server's node to bring backups into it.")
        }

        val size = zip.length()
        val sha256 = withContext(Dispatchers.IO) { PanoHostClient.sha256(zip) }
        val now = System.currentTimeMillis()
        val backupId = UUID.randomUUID().toString()
        val name = (meta.getString("name")?.trim()?.takeIf { it.isNotEmpty() } ?: "Pano Backup").take(NAME_LENGTH)
        val scope = BackupScope.fromId(meta.getString("scope")) ?: BackupScope.ALL
        val fileCount = (meta.getValue("fileCount") as? Number)?.toLong()

        val ticket = transferTicketStore.issue(
            userId = userId,
            serverId = serverId,
            nodeId = nodeId,
            serverUuid = target.serverUuid,
            path = PanelDownloadServerBackupAPI.VIRTUAL_PREFIX + backupId,
            direction = TransferDirection.UPLOAD,
            fileName = "$backupId.zip",
            ttlMs = TICKET_TTL_MS
        )

        ticket.spoolFile = zip

        try {
            fileClient.request(
                target,
                BackupImportMessage(
                    serverUuid = target.serverUuid,
                    backupId = backupId,
                    ticket = ticket.id,
                    name = name,
                    size = size,
                    sha256 = sha256,
                    createdAt = now,
                    scope = scope.name,
                    fileCount = fileCount
                ),
                importTimeoutMs(size)
            )
        } catch (e: PanoHostException) {
            throw e
        } catch (e: Exception) {
            throw PanoHostException(IMPORT_FAILED, message = e.message, cause = e)
        } finally {
            transferTicketStore.discard(ticket)
        }

        onProgress(size)

        databaseManager.serverBackupDao.add(
            ServerBackup(
                uuid = backupId,
                serverId = serverId,
                nodeId = nodeId,
                name = name,
                sizeBytes = size,
                sha256 = sha256,
                status = ServerBackupStatus.READY,
                createdBy = userId,
                createdAt = now,
                mode = BackupMode.FULL,
                scope = scope,
                fileCount = fileCount
            ),
            sqlClient
        )

        panelRealtimeHub.pushServerBackupsChanged(serverId)

        backupId
    }

    companion object {
        const val NOT_A_FULL_BACKUP = "NOT_A_FULL_BACKUP"
        const val IMPORT_UNSUPPORTED = "SERVER_BACKUP_IMPORT_UNSUPPORTED"
        const val IMPORT_FAILED = "SERVER_BACKUP_IMPORT_FAILED"
        const val NODE_TOO_OLD = "NODE_TOO_OLD"

        /** The node protocol that understands `BACKUP_IMPORT`. */
        const val MIN_IMPORT_PROTOCOL = com.panomc.platform.node.NodeProtocol.BACKUP_IMPORT_VERSION

        private const val NAME_LENGTH = 64

        /** The slowest link an import is still waited for: 256 KB a second, never less than ten minutes. */
        private fun importTimeoutMs(size: Long): Long = maxOf(10 * 60 * 1000L, size / (256L * 1024) * 1000L)

        const val FETCH_FAILED = "SERVER_BACKUP_FETCH_FAILED"

        /** The ticket outlives a large transfer (the sweep fails an expired ticket even mid-stream). */
        private const val TICKET_TTL_MS = 2L * 60 * 60 * 1000
        private const val FETCH_TIMEOUT_MS = TICKET_TTL_MS
    }
}
