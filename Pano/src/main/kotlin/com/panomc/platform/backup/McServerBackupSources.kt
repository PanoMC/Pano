package com.panomc.platform.backup

import com.panomc.platform.backup.remote.McServerBackupImporter
import com.panomc.platform.backup.remote.McServerBackupSource
import com.panomc.platform.backup.remote.McServerDirectUpload
import com.panomc.platform.node.NodeProtocol
import com.panomc.platform.node.message.BackupUploadMessage
import com.panomc.snapshot.SnapshotFormat
import java.util.Base64
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
import com.panomc.platform.node.transfer.StreamPipe
import com.panomc.platform.node.transfer.UploadRendezvous
import com.panomc.platform.node.transfer.UploadSource
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import com.panomc.platform.route.api.panel.server.backups.PanelDownloadServerBackupAPI
import com.panomc.platform.server.backup.BackupMode
import com.panomc.platform.server.backup.ServerBackupStatus
import com.panomc.platform.server.backup.ManagedServerBackupService
import com.panomc.platform.auth.panel.log.ServerBackupActionLog
import com.panomc.platform.route.api.panel.server.backups.PanelRestoreServerBackupAPI
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.sqlclient.SqlClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
    private val vertx: io.vertx.core.Vertx,
    private val databaseManager: DatabaseManager,
    private val fileClient: ManagedServerFileClient,
    private val transferTicketStore: TransferTicketStore,
    private val panelRealtimeHub: PanelRealtimeHub,
    private val backupService: ManagedServerBackupService,
    private val uploadTickets: PanoBackupUploadTickets
) {
    suspend fun source(serverId: Long, backupId: String, sqlClient: SqlClient, automatic: Boolean = false): McServerBackupSource {
        // The panel names a backup by its row id, the node and the automatic upload by its uuid; both are taken.
        val backup = databaseManager.serverBackupDao.getByUuid(backupId, sqlClient)
            ?: backupId.toLongOrNull()?.let { databaseManager.serverBackupDao.getById(it, sqlClient) }
            ?: throw NotExists()

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
            settings = server.settings.panoBackup,
            automatic = automatic,
            direct = directUpload(serverId, backup, sqlClient),
            onUploaded = { remoteId ->
                databaseManager.serverBackupDao.updatePanoBackupIdByUuid(backup.uuid, remoteId, databaseManager.getSqlClient())
                panelRealtimeHub.pushServerBackupsChanged(serverId)
            }
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

                ticket.awaitDownload()
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
     * The row remembers [panoBackupId], the copy it came from. With [restore] the server is then restored from it, the
     * way its Backups page restores one (a safety copy first; the server must be stopped: `SERVER_NOT_STOPPED`).
     */
    fun importer(userId: Long, panoBackupId: String? = null, restore: Boolean = false) = object : McServerBackupImporter {
        override suspend fun import(serverId: Long, zip: java.io.File, meta: JsonObject, onProgress: (Long) -> Unit): String =
            importFile(userId, panoBackupId, restore, serverId, zip, meta, onProgress)

        override suspend fun canStream(serverId: Long): Boolean {
            val sqlClient = databaseManager.getSqlClient()
            val target = runCatching { fileClient.resolve(serverId, sqlClient, ServerFeature.BACKUPS_CREATE) }.getOrNull() ?: return false
            val nodeId = target.nodeId ?: return false

            if (target.source != ServerFeatureSource.NODE) return false

            val node = databaseManager.nodeDao.getById(nodeId, sqlClient) ?: return false

            return node.protocolVersion >= NodeProtocol.STREAMED_IMPORT_VERSION
        }

        override suspend fun importStream(
            serverId: Long,
            declaredSize: Long?,
            produce: (java.io.OutputStream) -> JsonObject,
            onProgress: (Long) -> Unit
        ): String = importStreamed(userId, panoBackupId, restore, serverId, declaredSize, produce, onProgress)
    }

    /** The node that keeps server [serverId]'s backups, refused when there is none or it is too old to import. */
    private suspend fun importTarget(serverId: Long, sqlClient: SqlClient): ManagedServerFileClient.Target {
        val target = fileClient.resolve(serverId, sqlClient, ServerFeature.BACKUPS_CREATE)
        val nodeId = target.nodeId

        if (target.source != ServerFeatureSource.NODE || nodeId == null) {
            throw PanoHostException(IMPORT_UNSUPPORTED, message = "This server's backups are not kept by a node.")
        }

        val node = databaseManager.nodeDao.getById(nodeId, sqlClient)

        if (node == null || node.protocolVersion < MIN_IMPORT_PROTOCOL) {
            throw PanoHostException(NODE_TOO_OLD, message = "Update this server's node to bring backups into it.")
        }

        return target
    }

    private suspend fun importFile(
        userId: Long,
        panoBackupId: String?,
        restore: Boolean,
        serverId: Long,
        zip: java.io.File,
        meta: JsonObject,
        onProgress: (Long) -> Unit
    ): String {
        val sqlClient = databaseManager.getSqlClient()
        val target = importTarget(serverId, sqlClient)
        val nodeId = target.nodeId!!

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
                fileCount = fileCount,
                panoBackupId = panoBackupId
            ),
            sqlClient
        )

        panelRealtimeHub.pushServerBackupsChanged(serverId)

        if (restore) restoreImported(serverId, backupId, userId)

        return backupId
    }

    /**
     * The streamed import (protocol [NodeProtocol.STREAMED_IMPORT_VERSION]): the node takes the bytes [produce] writes
     * as they come (stage RECEIVE, through an upload ticket joined to a [StreamPipe]), Pano keeps none of them. Once
     * [produce] returned the verified meta, the node's checksum is compared with it and the file committed with that
     * meta; anything that fails on the way deletes what the node received.
     */
    private suspend fun importStreamed(
        userId: Long,
        panoBackupId: String?,
        restore: Boolean,
        serverId: Long,
        declaredSize: Long?,
        produce: (java.io.OutputStream) -> JsonObject,
        onProgress: (Long) -> Unit
    ): String {
        val sqlClient = databaseManager.getSqlClient()
        val target = importTarget(serverId, sqlClient)
        val nodeId = target.nodeId!!
        val backupId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val upload = UploadRendezvous(declaredSize)
        val pipe = StreamPipe(vertx)

        val ticket = transferTicketStore.issue(
            userId = userId,
            serverId = serverId,
            nodeId = nodeId,
            serverUuid = target.serverUuid,
            path = PanelDownloadServerBackupAPI.VIRTUAL_PREFIX + backupId,
            direction = TransferDirection.UPLOAD,
            fileName = "$backupId.zip",
            ttlMs = TICKET_TTL_MS,
            upload = upload
        )

        upload.source.complete(UploadSource(pipe.stream, "$backupId.zip"))

        fun stage(stage: String, meta: JsonObject? = null, size: Long? = null, sha256: String? = null) = BackupImportMessage(
            serverUuid = target.serverUuid,
            backupId = backupId,
            ticket = ticket.id,
            name = (meta?.getString("name")?.trim()?.takeIf { it.isNotEmpty() } ?: "Pano Backup").take(NAME_LENGTH),
            size = size,
            sha256 = sha256,
            createdAt = now,
            scope = (BackupScope.fromId(meta?.getString("scope")) ?: BackupScope.ALL).name,
            fileCount = (meta?.getValue("fileCount") as? Number)?.toLong(),
            stage = stage
        )

        val (meta, received) = try {
            coroutineScope {
                val producer = async(Dispatchers.IO) {
                    try {
                        CountingOutputStream(pipe.output, onProgress).use { produce(it) }
                    } catch (e: Throwable) {
                        pipe.fail(e)

                        throw e
                    }
                }

                val reply = try {
                    fileClient.request(target, stage(BackupImportMessage.STAGE_RECEIVE), STREAMED_IMPORT_TIMEOUT_MS)
                } catch (e: Throwable) {
                    // The node stopped taking the bytes (its disk, a crash): the reading side ends, the producer's
                    // next write fails. Its own error, when it had one first, is the better reason.
                    pipe.cancel(e)

                    val produced = runCatching { producer.await() }.exceptionOrNull()

                    throw produced?.takeIf { it !is java.io.IOException || it is com.panomc.platform.archive.PanoArcException } ?: e
                }

                producer.await() to reply
            }
        } catch (e: Throwable) {
            abortStreamed(target, stage(BackupImportMessage.STAGE_ABORT))

            throw importError(e)
        } finally {
            transferTicketStore.discard(ticket)
        }

        val size = received.getLong("sizeBytes") ?: 0L
        val sha256 = received.getString("sha256") ?: ""
        val expected = meta.getString("sha256")

        // An archive's meta names the zip it was made of; a zip rebuilt from a snapshot has none to compare with.
        if (expected != null && meta.getString("mode") != BackupMode.SNAPSHOT.name && !expected.equals(sha256, ignoreCase = true)) {
            abortStreamed(target, stage(BackupImportMessage.STAGE_ABORT))

            throw PanoHostException(PanoHostException.INTEGRITY_FAILED, message = "The backup that reached the node does not match its checksum.")
        }

        try {
            fileClient.request(target, stage(BackupImportMessage.STAGE_COMMIT, meta, size, sha256))
        } catch (e: Throwable) {
            abortStreamed(target, stage(BackupImportMessage.STAGE_ABORT))

            throw importError(e)
        }

        val scope = BackupScope.fromId(meta.getString("scope")) ?: BackupScope.ALL

        databaseManager.serverBackupDao.add(
            ServerBackup(
                uuid = backupId,
                serverId = serverId,
                nodeId = nodeId,
                name = (meta.getString("name")?.trim()?.takeIf { it.isNotEmpty() } ?: "Pano Backup").take(NAME_LENGTH),
                sizeBytes = size,
                sha256 = sha256,
                status = ServerBackupStatus.READY,
                createdBy = userId,
                createdAt = now,
                mode = BackupMode.FULL,
                scope = scope,
                fileCount = (meta.getValue("fileCount") as? Number)?.toLong(),
                panoBackupId = panoBackupId
            ),
            databaseManager.getSqlClient()
        )

        panelRealtimeHub.pushServerBackupsChanged(serverId)

        if (restore) restoreImported(serverId, backupId, userId)

        return backupId
    }

    private suspend fun abortStreamed(target: ManagedServerFileClient.Target, message: BackupImportMessage) {
        runCatching { fileClient.request(target, message) }
    }

    /** What a failed streamed import reports: the node's full disk as `NOT_ENOUGH_SPACE` with its numbers. */
    private fun importError(e: Throwable): Throwable = when (e) {
        is PanoHostException, is com.panomc.platform.archive.PanoArcException, is PanoDiskFullException -> e
        is com.panomc.platform.error.NotEnoughSpace -> PanoHostException(
            PanoDiskFullException.CODE,
            extras = (runCatching { JsonObject(e.encode()).getJsonObject("error")?.getJsonObject("details") }.getOrNull() ?: JsonObject())
                .put("where", "node"),
            message = "The server's disk does not have room for this backup.",
            cause = e
        )
        else -> PanoHostException(IMPORT_FAILED, message = e.message, cause = e)
    }

    /** Counts what passes, for the job's progress. */
    private class CountingOutputStream(out: java.io.OutputStream, private val onProgress: (Long) -> Unit) : java.io.FilterOutputStream(out) {
        private var count = 0L

        override fun write(b: Int) {
            out.write(b)
            onProgress(++count)
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            out.write(b, off, len)
            count += len
            onProgress(count)
        }
    }

    /**
     * How a node of protocol [NodeProtocol.BACKUP_UPLOAD_VERSION] or newer sends [backup] to Pano Backup itself: under a
     * ticket answered by the job's handler, the bytes straight from the node to the storage. Null for an older node or
     * a backup the server's plugin keeps (then it is pulled here and uploaded from Pano).
     */
    private suspend fun directUpload(serverId: Long, backup: ServerBackup, sqlClient: SqlClient): McServerDirectUpload? {
        val nodeId = backup.nodeId ?: return null
        val node = databaseManager.nodeDao.getById(nodeId, sqlClient) ?: return null

        if (node.protocolVersion < NodeProtocol.BACKUP_UPLOAD_VERSION) return null

        return McServerDirectUpload { request, handler ->
            val target = fileClient.resolve(serverId, databaseManager.getSqlClient())

            if (target.nodeId != nodeId) {
                throw PanoHostException(FETCH_FAILED, message = "The backup's node no longer serves this server.")
            }

            val encryption = request.encryption
            val snapshot = request.snapshot

            // The node may only ever hold the server's own repository key, never the Pano's.
            if (snapshot != null && !snapshot.subjectScoped) {
                throw PanoHostException(FETCH_FAILED, message = "The snapshot key is not the server's own; the node does not get it.")
            }

            val ticket = uploadTickets.issue(nodeId, handler)
            val base64 = Base64.getEncoder()

            try {
                fileClient.request(
                    target,
                    BackupUploadMessage(
                        serverUuid = target.serverUuid,
                        backupId = backup.uuid,
                        ticket = ticket.id,
                        mode = if (snapshot == null) UPLOAD_MODE_ARCHIVE else UPLOAD_MODE_SNAPSHOT,
                        meta = request.meta.encode(),
                        producer = request.producer,
                        instanceName = request.instanceName,
                        encryptionKey = encryption?.let { base64.encodeToString(it.masterKey) },
                        kdfSalt = encryption?.let { base64.encodeToString(it.kdf.salt) },
                        kdfT = encryption?.kdf?.t,
                        kdfMKiB = encryption?.kdf?.mKiB,
                        kdfP = encryption?.kdf?.p,
                        snapshotId = snapshot?.id,
                        snapshotKeyId = snapshot?.keyId,
                        snapshotKey = snapshot?.let { base64.encodeToString(it.key) },
                        repoConfig = snapshot?.let { String(SnapshotFormat.configJson(it.repoConfig), Charsets.UTF_8) },
                        manifestUrl = snapshot?.manifestUrl,
                        snapshotName = request.snapshotName,
                        createdAt = request.createdAt
                    ),
                    uploadTimeoutMs(backup.sizeBytes)
                )
            } finally {
                uploadTickets.discard(ticket)
            }
        }
    }

    /** Whether [serverId] is a node-kept server that is stopped now, which a restore out of Pano Backup needs. */
    suspend fun canRestoreNow(serverId: Long, sqlClient: SqlClient? = null): Boolean {
        val target = fileClient.resolve(serverId, sqlClient ?: databaseManager.getSqlClient(), ServerFeature.BACKUPS_RESTORE)

        return target.source == ServerFeatureSource.NODE && PanelRestoreServerBackupAPI.isStopped(target.server.processState)
    }

    private suspend fun restoreImported(serverId: Long, backupId: String, userId: Long) {
        val sqlClient = databaseManager.getSqlClient()
        val backup = databaseManager.serverBackupDao.getByUuid(backupId, sqlClient) ?: throw NotExists()

        // Checked when it was asked; the server may have been started while the copy was coming down.
        if (!canRestoreNow(serverId, sqlClient)) {
            throw PanoHostException(SERVER_NOT_STOPPED, message = "Stop the server to restore it; the backup is in its backups now.")
        }

        backupService.restore(fileClient.resolve(serverId, sqlClient, ServerFeature.BACKUPS_RESTORE), backup, userId, sqlClient)

        val username = databaseManager.userDao.getUsernameFromUserId(userId, sqlClient) ?: return

        databaseManager.panelActivityLogDao.add(
            ServerBackupActionLog(userId, username, serverId, ServerBackupActionLog.ACTION_RESTORE, backup.uuid, backup.name),
            sqlClient
        )
    }

    companion object {
        const val NOT_A_FULL_BACKUP = "NOT_A_FULL_BACKUP"
        const val IMPORT_UNSUPPORTED = "SERVER_BACKUP_IMPORT_UNSUPPORTED"
        const val IMPORT_FAILED = "SERVER_BACKUP_IMPORT_FAILED"
        const val NODE_TOO_OLD = "NODE_TOO_OLD"
        const val SERVER_NOT_STOPPED = "SERVER_NOT_STOPPED"

        /** The node protocol that understands `BACKUP_IMPORT`. */
        const val MIN_IMPORT_PROTOCOL = com.panomc.platform.node.NodeProtocol.BACKUP_IMPORT_VERSION

        private const val NAME_LENGTH = 64

        const val UPLOAD_MODE_ARCHIVE = "ARCHIVE"
        const val UPLOAD_MODE_SNAPSHOT = "SNAPSHOT"

        /** A direct upload is waited for down to 128 KB a second end to end, never less than thirty minutes. */
        private fun uploadTimeoutMs(size: Long): Long = maxOf(30 * 60 * 1000L, size / (128L * 1024) * 1000L)

        /** The slowest link an import is still waited for: 256 KB a second, never less than ten minutes. */
        /** A streamed import ends when its bytes stop coming (the relay's idle watchdog), not after a total time. */
        private const val STREAMED_IMPORT_TIMEOUT_MS = 48L * 60 * 60 * 1000

        private fun importTimeoutMs(size: Long): Long = maxOf(10 * 60 * 1000L, size / (256L * 1024) * 1000L)

        const val FETCH_FAILED = "SERVER_BACKUP_FETCH_FAILED"

        /** How long the node has to start sending; once it has, the transfer runs as long as it takes. */
        private const val TICKET_TTL_MS = 2L * 60 * 60 * 1000
    }
}
