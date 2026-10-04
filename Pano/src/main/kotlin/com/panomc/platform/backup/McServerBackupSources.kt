package com.panomc.platform.backup

import com.panomc.platform.backup.remote.McServerBackupSource
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
 * Turns a managed MC server backup (pano-node or agent-lite, FULL zip only) into a
 * [McServerBackupSource]: the zip is pulled through the same transfer-ticket path a browser
 * download uses, into a file Pano keeps, and checked against the sha256 the source reported.
 */
@Lazy
@Component
@Scope(value = ConfigurableBeanFactory.SCOPE_SINGLETON)
class McServerBackupSources(
    private val databaseManager: DatabaseManager,
    private val fileClient: ManagedServerFileClient,
    private val transferTicketStore: TransferTicketStore
) {
    suspend fun source(serverId: Long, backupId: String, sqlClient: SqlClient): McServerBackupSource {
        val backup = databaseManager.serverBackupDao.getByUuid(backupId, sqlClient) ?: throw NotExists()

        if (backup.serverId != serverId || backup.status != ServerBackupStatus.READY) {
            throw NotExists()
        }

        if (backup.mode != BackupMode.FULL) {
            throw PanoHostException(NOT_A_FULL_BACKUP)
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
            meta = meta
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

    companion object {
        const val NOT_A_FULL_BACKUP = "NOT_A_FULL_BACKUP"
        const val FETCH_FAILED = "SERVER_BACKUP_FETCH_FAILED"

        /** The ticket outlives a large transfer (the sweep fails an expired ticket even mid-stream). */
        private const val TICKET_TTL_MS = 2L * 60 * 60 * 1000
        private const val FETCH_TIMEOUT_MS = TICKET_TTL_MS
    }
}
