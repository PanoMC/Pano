package com.panomc.platform.backup.remote

import com.panomc.platform.backup.PanoBackupException
import com.panomc.platform.backup.PanoBackupJob
import com.panomc.platform.backup.PanoBackupRunner
import com.panomc.platform.backup.PanoBackupService
import com.panomc.platform.backup.PanoBackupStore
import com.panomc.platform.model.PageRequest
import com.panomc.platform.server.backup.PanoBackupRetentionRule
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

/** The panomc.com account this Pano is connected to (from `panoAccount`; never the token). */
data class ConnectedAccount(val username: String, val platformId: String) {
    fun toJson(): JsonObject = JsonObject().put("username", username).put("platformId", platformId)
}

/**
 * Pano Backup + transfer of a Pano over its panomc.com platform connection (host-api.md §Connected
 * Pano; the connection's account = the account the backups belong to):
 * - upload: archive to a temp file — E2E-encrypted with the saved passphrase, or a plain archive
 *   when none is set (recommended, not required) — → [PanoBackupTarget] (presigned multipart with
 *   retry, stop polled between parts → complete); restore: download → verify → the local restore
 *   flow of [backups];
 * - snapshots (the default mode): deduplicated, sealed chunks in packs — only what the account's storage
 *   does not hold yet is uploaded; a restore rebuilds a plain archive from the chunks and takes the normal restore flow;
 * - schedule per [RemoteBackupSettings] (the plan has no frequency rule; a failed scheduled upload
 *   waits [RETRY_AFTER_FAILURE_MS] before the next attempt, one refused for a full storage or a missing plan
 *   waits for the next scheduled slot unless the overview shows more room);
 * - transfer: a plain archive pushed into one of the account's Pano workloads (the admin confirmed
 *   with the password in the panel, Pano Host restores it at once and the job follows it to the end);
 * - managed MC server backups wrapped as `kind: mc-server`, encrypted and uploaded (the server's own retention
 *   applied at Pano Backup afterwards), and brought back into a managed server's backups ([startMcImport]).
 *
 * Every archive/restore/upload runs as the one [PanoBackupService] job (single flight). Not
 * connected → [PanoHostException.CONNECT_REQUIRED].
 */
/** A snapshot rebuilt into a plain archive, waiting to be downloaded until [expiresAt]. */
class SnapshotDownload(val jobId: String, val file: File, val fileName: String, val expiresAt: Long)

class PanoRemoteBackupService(
    private val client: PanoHostClient,
    private val stateStore: RemoteStateStore,
    /** Null in setup mode: nothing is uploaded there. */
    private val passphraseFile: PassphraseFile?,
    private val backups: PanoBackupRunner,
    private val tempDir: () -> File,
    /** The connected account (null = not connected). */
    private val account: () -> ConnectedAccount?,
    private val instanceName: () -> String,
    private val panoVersion: String,
    private val clock: () -> Long = System::currentTimeMillis,
    private val logger: Logger = LoggerFactory.getLogger(PanoRemoteBackupService::class.java),
    /** A scheduled upload ended (true = uploaded): the platform records it in the activity log. */
    private val onScheduledSettled: suspend (ok: Boolean) -> Unit = {}
) {
    private val stateLock = Mutex()
    private val mcQueue = ConcurrentLinkedQueue<McServerBackupSource>()
    private val target = PanoBackupTarget(client)

    /** The last `GET /host/connected/backups` answer and when (plan + usage for the overview and the schedule). */
    @Volatile
    private var listCache: Pair<Long, JsonObject>? = null

    @Volatile
    private var lastScheduledFailureAt: Long? = null

    /** The archive of the last snapshot download job, waiting to be fetched by the panel. */
    @Volatile
    private var download: SnapshotDownload? = null

    /** A scheduled run the storage refused (full, or no plan): the next attempt is the next slot, or earlier when the overview changes. */
    private class StorageRefusal(val at: Long, val tierId: String?, val freeBytes: Long?) {
        companion object {
            /** The refusal at [at], remembering the plan and free bytes of the overview [list] it happened on. */
            fun of(at: Long, list: JsonObject?) = StorageRefusal(
                at,
                list?.getJsonObject("tier")?.getString("id"),
                (list?.getJsonObject("usage")?.getValue("freeBytes") as? Number)?.toLong()
            )
        }
    }

    @Volatile
    private var storageRefusal: StorageRefusal? = null

    val producer: String get() = "pano/$panoVersion"

    // State

    suspend fun state(): RemoteBackupState = stateStore.load()

    private suspend fun update(change: (RemoteBackupState) -> RemoteBackupState): RemoteBackupState = stateLock.withLock {
        change(stateStore.load()).also { stateStore.save(it) }
    }

    fun isConnected(): Boolean = account() != null && client.isConnected()

    fun passphraseSet(): Boolean = passphraseFile?.isSet() == true

    /**
     * Overview for the UI (never the token or the passphrase): `{connected, account, plan {tier,
     * subscription} | null, usage {used, reserved, quota, free} | null, settings, passphraseSet,
     * lastUploadAt, hostError?}` — plan + usage from Pano Host (cached [LIST_CACHE_MS]; `hostError` when it
     * could not be asked).
     */
    suspend fun status(fresh: Boolean = false): JsonObject {
        val state = state()
        val connected = isConnected()
        val result = JsonObject()
            .put("connected", connected)
            .put("account", if (connected) account()?.toJson() else null)
            .put("plan", null)
            .put("usage", null)
            .put("settings", state.settings.toJson())
            .put("passphraseSet", passphraseFile?.isSet() == true)
            .put("lastUploadAt", state.lastUploadAt)

        if (!connected) {
            return result
        }

        try {
            val list = cachedList(fresh)
            val usage = list.getJsonObject("usage") ?: JsonObject()

            result.put("plan", planOf(list))
            result.put(
                "usage", JsonObject()
                    .put("used", usage.getLong("usedBytes", 0L))
                    .put("reserved", usage.getLong("reservedBytes", 0L))
                    .put("quota", usage.getLong("quotaBytes"))
                    .put("free", usage.getLong("freeBytes"))
            )
        } catch (e: PanoHostException) {
            if (e.code == PanoHostException.CONNECT_REQUIRED) result.put("connected", false).put("account", null)

            result.put("hostError", JsonObject().put("code", e.code).mergeIn(e.extras))
        }

        return result
    }

    private fun planOf(list: JsonObject): JsonObject? {
        val tier = list.getJsonObject("tier") ?: return null

        return JsonObject().put("tier", tier).put("subscription", list.getJsonObject("subscription"))
    }

    private suspend fun cachedList(fresh: Boolean): JsonObject {
        val now = clock()

        if (!fresh) listCache?.takeIf { now - it.first in 0 until LIST_CACHE_MS }?.let { return it.second }

        return listBackups()
    }

    suspend fun saveSettings(settings: RemoteBackupSettings) {
        update { it.copy(settings = settings) }

        // The owner changed something: a run held back for a full storage gets its chance now.
        storageRefusal = null
    }

    fun setPassphrase(passphrase: CharArray?) {
        val file = passphraseFile ?: throw PanoBackupException(NOT_AVAILABLE)

        try {
            file.write(passphrase)
        } finally {
            passphrase?.fill('\u0000')
        }
    }

    private fun requireConnected() {
        if (!isConnected()) throw PanoHostException(PanoHostException.CONNECT_REQUIRED, extras = JsonObject().put("reason", "NOT_CONNECTED"))
    }

    // Pano Backup

    /** `{instanceId, tier, subscription, usage, panos[]}` of the account (every Pano's backups; `own` = this Pano's). */
    suspend fun listBackups(): JsonObject {
        requireConnected()

        return client.listBackups().also { listCache = clock() to it }
    }

    suspend fun deleteBackup(id: String) {
        requireConnected()
        client.deleteBackup(id)
        listCache = null
    }

    /**
     * Archives this Pano (encrypted with the saved passphrase, plain without one) and uploads it;
     * throws BUSY when a job runs. [scheduled]: started by the schedule, not by an admin (told to the account's storage
     * as the archive's `trigger`, so the lists can show it).
     */
    fun startUpload(scheduled: Boolean = false): PanoBackupJob {
        val temp = tempFile("upload")

        return backups.startTask(PanoBackupJob.Type.UPLOAD, cleanup = { delete(temp) }) { job -> upload(job, temp, scheduled) }
    }

    private suspend fun upload(job: PanoBackupJob, temp: File, scheduled: Boolean) {
        requireConnected()

        val passphrase = passphraseFile?.read()
        val encrypted = passphrase != null && passphrase.isNotEmpty()

        try {
            job.phase = PanoBackupService.PHASE_ARCHIVING
            backups.archiveTo(temp, passphrase)
        } finally {
            passphrase?.fill('\u0000')
        }

        job.phase = PanoBackupService.PHASE_UPLOADING
        job.bytesTotal = temp.length()

        val summary = JsonObject()
            .put("panoVersion", panoVersion)
            .put("encrypted", encrypted)
            .put("trigger", if (scheduled) TRIGGER_SCHEDULED else TRIGGER_MANUAL)

        try {
            job.remoteId = target.put(
                temp,
                StoredArchive(LocalBackupTarget.KIND_PANO_INSTANCE, null, encrypted = encrypted, summary = summary),
                onStarted = { job.remoteId = it }
            ) { job.bytesDone = it }
        } finally {
            listCache = null
        }

        update { it.copy(lastUploadAt = clock(), lastFullBytes = temp.length()) }
    }

    // Snapshots

    /**
     * Takes a deduplicated snapshot of this Pano and uploads what the account's storage lacks ([name] is shown with it);
     * throws BUSY when a job runs. Pano Host is asked first, so a full storage or a missing plan refuses before any work.
     */
    fun startSnapshot(name: String? = null): PanoBackupJob {
        val dir = workDir("snapshot")

        return backups.startTask(PanoBackupJob.Type.SNAPSHOT, cleanup = { deleteTree(dir) }) { job -> snapshot(job, dir, name) }
    }

    private suspend fun snapshot(job: PanoBackupJob, dir: File, name: String?) {
        requireConnected()

        job.phase = PanoBackupService.PHASE_ARCHIVING

        val session = try {
            client.startSnapshot(name)
        } finally {
            listCache = null
        }

        job.remoteId = session.id

        val stats = try {
            withContext(Dispatchers.IO) { dir.mkdirs() }

            val content = backups.prepareSnapshot(File(dir, "dump.sql"))

            job.phase = PanoBackupService.PHASE_UPLOADING

            withContext(Dispatchers.IO) {
                SnapshotUpload(client, session, content, dir, name ?: SNAPSHOT_MANUAL, producer, clock()) { done, total ->
                    job.bytesDone = done
                    job.bytesTotal = total
                }.run()
            }.stats
        } catch (e: Throwable) {
            // Best effort: the session would only expire after a day, and while it is open no other snapshot can start.
            withContext(NonCancellable) { runCatching { client.deleteSnapshot(session.id) } }

            throw e
        }

        try {
            val settled = client.completeSnapshot(session.id, stats)

            if (settled.getString("status") != SNAPSHOT_DONE) {
                // Pano Host failed it itself; the error is its own (the row is deleted below, best effort).
                throw PanoHostException(
                    SNAPSHOT_FAILED,
                    message = settled.getString("error") ?: "Pano Backup could not settle the snapshot.",
                    extras = JsonObject().put("snapshotId", session.id).put("error", settled.getString("error")).put("status", settled.getString("status"))
                )
            }
        } catch (e: Throwable) {
            withContext(NonCancellable) { runCatching { client.deleteSnapshot(session.id) } }

            throw e
        } finally {
            listCache = null
        }

        update { it.copy(lastUploadAt = clock()) }
    }

    /**
     * `{snapshots[view], repository {storedBytes}}`: this Pano's snapshots (`own: true`) and, from the overview, the
     * other Panos' of the account (`own: false`), newest first.
     */
    suspend fun listSnapshots(): JsonObject {
        requireConnected()

        val own = client.listSnapshots()
        val snapshots = ArrayList<JsonObject>()
        val ids = HashSet<String>()

        (own.getJsonArray("snapshots") ?: JsonArray()).forEach { view ->
            view as JsonObject

            ids.add(view.getString("id"))
            snapshots.add(view.copy().put("own", true))
        }

        try {
            (cachedList(fresh = false).getJsonArray("panos") ?: JsonArray()).forEach { pano ->
                pano as JsonObject

                if (pano.getBoolean("current") == true) return@forEach

                (pano.getJsonArray("snapshots") ?: JsonArray()).forEach { view ->
                    view as JsonObject

                    if (ids.add(view.getString("id"))) snapshots.add(view.copy().put("own", false))
                }
            }
        } catch (e: PanoHostException) {
            logger.warn("Could not read the account's other Panos' snapshots: ${e.code}")
        }

        return JsonObject()
            .put("snapshots", JsonArray(snapshots.sortedByDescending { it.getLong("createdAt") ?: 0L }))
            .put("repository", own.getJsonObject("repository") ?: JsonObject().put("storedBytes", 0L))
    }

    /** One page of this Pano's rows (archives and snapshots), see [RemoteBackupListing]. */
    suspend fun listAccount(page: PageRequest): Map<String, Any?> {
        val backups = listBackups()

        // The snapshots are an add-on to the list: a failure to read them leaves the archives, as before.
        val snapshots = try {
            listSnapshots()
        } catch (e: PanoHostException) {
            logger.warn("Could not read the snapshots for the Pano Backup list: ${e.code}")
            JsonObject()
        }

        return RemoteBackupListing.build(backups, snapshots, page)
    }

    suspend fun deleteSnapshot(id: String) {
        requireConnected()
        client.deleteSnapshot(id)
        listCache = null
    }

    /**
     * Restores snapshot [id] (any finished snapshot of the account) over this Pano through [service]: its chunks are read
     * and checked, a plain `pano-instance` archive is rebuilt from them in the temp folder and takes the local restore
     * flow ([safetyArchive], [maintenance], restart).
     */
    fun startSnapshotRestore(
        id: String,
        service: PanoBackupRunner = backups,
        safetyArchive: Boolean = true,
        maintenance: Boolean = true
    ): PanoBackupJob {
        val temp = tempFile("snapshot-restore")
        val dir = workDir("snapshot-restore")

        return service.startTask(PanoBackupJob.Type.RESTORE, cleanup = { delete(temp); deleteTree(dir) }) { job ->
            requireConnected()

            job.remoteId = id
            job.phase = PanoBackupService.PHASE_DOWNLOADING

            val session = client.openSnapshot(id)

            withContext(Dispatchers.IO) {
                SnapshotRestoreArchive(client, session, dir) { done, total ->
                    job.bytesDone = done
                    job.bytesTotal = total
                }.build(temp)
            }

            job.phase = PanoBackupService.PHASE_RESTORING
            job.bytesDone = 0
            job.bytesTotal = 0
            job.backupId = service.restoreFrom(temp, null, safetyArchive, maintenance)?.id
        }
    }

    /**
     * Builds snapshot [id] (any finished snapshot of the account) into a plain `.panoarc` for the admin to download: the
     * same rebuild as [startSnapshotRestore], but nothing is restored. The file waits in the temp folder for
     * [takeDownload] for [DOWNLOAD_TTL_MS] (one at a time: the next download job replaces it).
     */
    fun startSnapshotDownload(id: String): PanoBackupJob {
        val temp = tempFile("snapshot-download")
        val dir = workDir("snapshot-download")
        var ready = false

        return backups.startTask(PanoBackupJob.Type.DOWNLOAD, cleanup = { deleteTree(dir); if (!ready) delete(temp) }) { job ->
            requireConnected()
            discardDownload()

            job.remoteId = id
            job.phase = PanoBackupService.PHASE_DOWNLOADING

            val session = client.openSnapshot(id)

            withContext(Dispatchers.IO) {
                SnapshotRestoreArchive(client, session, dir) { done, total ->
                    job.bytesDone = done
                    job.bytesTotal = total
                }.build(temp)
            }

            ready = true
            download = SnapshotDownload(job.id, temp, "pano-snapshot-${id.take(8)}${PanoBackupStore.EXTENSION}", clock() + DOWNLOAD_TTL_MS)
        }
    }

    /** The archive built by download job [jobId], or null when it is not (or no longer) there. */
    fun takeDownload(jobId: String, now: Long = clock()): SnapshotDownload? =
        download?.takeIf { it.jobId == jobId && it.expiresAt > now && it.file.isFile }

    /** Removes the pending download file, once it was sent or when it is replaced or expired. */
    fun discardDownload(jobId: String? = null) {
        val current = download ?: return

        if (jobId != null && current.jobId != jobId) return

        download = null
        current.file.delete()
    }

    // Moving out of Pano Host

    /** `{workloads}`: the account's Pano Host instances, each saying whether it can be exported now. */
    suspend fun listHostInstances(): JsonObject {
        requireConnected()

        return client.listExports()
    }

    /**
     * Moves Pano Host instance [workloadId] here (setup-ui "Transfer → Pano Host"): asks for its export (an
     * export still valid is reused), waits until Pano Host has made it, downloads the plain archive and
     * restores it through [service]. Exports are plain, so no passphrase; the instance on Pano Host keeps
     * running — its subscription is the owner's to end.
     */
    fun startHostMove(
        workloadId: String,
        service: PanoBackupRunner = backups,
        safetyArchive: Boolean = false,
        maintenance: Boolean = false,
        pollIntervalMs: Long = EXPORT_POLL_MS,
        timeoutMs: Long = EXPORT_TIMEOUT_MS
    ): PanoBackupJob {
        val temp = tempFile("move")

        return service.startTask(PanoBackupJob.Type.RESTORE, cleanup = { delete(temp) }) { job ->
            requireConnected()

            job.remoteId = workloadId
            job.phase = PHASE_EXPORTING

            var export = client.startExport(workloadId).getJsonObject("export")
            val deadline = clock() + timeoutMs

            while (export?.getString("status") != EXPORT_DONE) {
                when (export?.getString("status")) {
                    EXPORT_FAILED -> throw PanoHostException(EXPORT_FAILED_CODE, message = export.getString("error") ?: "The export failed.")
                    null -> throw PanoHostException(EXPORT_FAILED_CODE, message = "Pano Host has no export of this instance.")
                }

                if (clock() > deadline) {
                    throw PanoHostException(EXPORT_FAILED_CODE, message = "The export did not finish in time.")
                }

                delay(pollIntervalMs)

                export = client.exportStatus(workloadId).getJsonObject("export")
            }

            val url = export.getString("url") ?: throw PanoHostException(EXPORT_FAILED_CODE, message = "The export has no download link.")

            job.phase = PanoBackupService.PHASE_DOWNLOADING
            job.bytesTotal = export.getLong("sizeBytes") ?: 0L

            client.download(url, temp, export.getLong("sizeBytes"), null) { job.bytesDone = it }

            job.phase = PanoBackupService.PHASE_RESTORING
            job.bytesDone = 0
            job.bytesTotal = 0
            job.backupId = service.restoreFrom(temp, null, safetyArchive, maintenance)?.id
        }
    }

    /**
     * Restores Pano Backup [id] (any backup of the account) over this Pano through [service] (the
     * panel's, or setup mode's with its own target database). [passphrase] null = the saved one.
     */
    fun startRestore(
        id: String,
        passphrase: CharArray?,
        service: PanoBackupRunner = backups,
        safetyArchive: Boolean = true,
        maintenance: Boolean = true
    ): PanoBackupJob {
        val temp = tempFile("restore")
        val key = passphrase ?: passphraseFile?.read()

        return service.startTask(PanoBackupJob.Type.RESTORE, cleanup = { key?.fill('\u0000'); delete(temp) }) { job ->
            requireConnected()

            job.remoteId = id
            job.phase = PanoBackupService.PHASE_DOWNLOADING

            target.fetch(id, temp) { job.bytesDone = it }

            job.phase = PanoBackupService.PHASE_RESTORING
            job.bytesDone = 0
            job.bytesTotal = 0
            job.backupId = service.restoreFrom(temp, key, safetyArchive, maintenance)?.id
        }
    }

    // Transfer

    /** `{workloads}`: the account's Pano workloads this Pano can be transferred into. */
    suspend fun listWorkloads(): JsonObject {
        requireConnected()

        return client.listWorkloads()
    }

    /** `{transfers}` pushed by this Pano. */
    suspend fun listTransfers(): JsonObject {
        requireConnected()

        return client.listTransfers()
    }

    suspend fun getTransfer(id: String): JsonObject {
        requireConnected()

        return client.getTransfer(id)
    }

    /** E-mails the owner a transfer code for [workloadId]; `{email, expiresAt, resendAfter}`. */
    suspend fun requestTransferCode(workloadId: String): JsonObject {
        requireConnected()

        return client.requestTransferCode(workloadId)
    }

    /** Checks [code] at Pano Host; the returned single-use grant is passed to [startTransfer]. */
    suspend fun verifyTransferCode(workloadId: String, code: String): String {
        requireConnected()

        return client.verifyTransferCode(workloadId, code)
    }

    suspend fun cancelTransfer(id: String) {
        requireConnected()
        client.cancelTransfer(id)
    }

    /**
     * Pushes a plain archive of this Pano into workload [workloadId]; [grant] is what [verifyTransferCode] returned (the
     * admin confirmed with the e-mailed code). Completing the upload makes Pano Host start the restore at once
     * (`RESTORING`), and the job follows the transfer every
     * [pollIntervalMs] until Pano Host says `DONE` (job DONE) or `FAILED` (job FAILED with `TRANSFER_FAILED`), or
     * [timeoutMs] runs out (`TRANSFER_TIMEOUT`; the restore itself is not stopped). Jobs live in memory, so a
     * restart of this Pano ends the job; the transfer goes on at Pano Host and shows in [listTransfers].
     */
    fun startTransfer(
        workloadId: String,
        grant: String,
        pollIntervalMs: Long = TRANSFER_POLL_MS,
        timeoutMs: Long = TRANSFER_TIMEOUT_MS
    ): PanoBackupJob {
        val temp = tempFile("transfer")

        return backups.startTask(PanoBackupJob.Type.TRANSFER, cleanup = { delete(temp) }) { job ->
            requireConnected()

            // Checked before archiving, so a wrong target never costs a full archive.
            val workloads = client.listWorkloads().getJsonArray("workloads") ?: JsonArray()

            if (workloads.none { (it as? JsonObject)?.getString("id") == workloadId }) {
                throw PanoHostException(WORKLOAD_NOT_FOUND, extras = JsonObject().put("workloadId", workloadId))
            }

            job.phase = PanoBackupService.PHASE_ARCHIVING
            backups.archiveTo(temp, null)

            job.phase = PanoBackupService.PHASE_UPLOADING
            job.bytesTotal = temp.length()

            val session = client.startTransfer(workloadId, temp.length(), grant)

            job.remoteId = session.id

            var transfer: JsonObject

            // Until the restore has started the transfer can still be canceled; a failed complete leaves nothing waiting.
            try {
                client.uploadParts(temp, session) { job.bytesDone = it }
                transfer = client.completeTransfer(session.id)
            } catch (e: Throwable) {
                runCatching { client.cancelTransfer(session.id) }

                throw e
            }

            job.phase = PHASE_TRANSFER_RESTORING
            job.bytesDone = 0
            job.bytesTotal = 0
            reportTransfer(job, transfer)

            val deadline = clock() + timeoutMs

            while (transfer.getString("status") != TRANSFER_DONE) {
                val status = transfer.getString("status")

                if (status == null || status in TRANSFER_ENDED) {
                    throw PanoHostException(TRANSFER_FAILED_CODE, message = transfer.getString("error") ?: "The transfer failed.")
                }

                if (clock() > deadline) {
                    throw PanoHostException(TRANSFER_TIMEOUT_CODE, message = "The transfer did not finish in time.")
                }

                delay(pollIntervalMs)

                transfer = client.getTransfer(session.id)
                reportTransfer(job, transfer)
            }
        }
    }

    /** Takes the optional `progress {percent, phase}` of a transfer view; nothing is invented when it is absent. */
    private fun reportTransfer(job: PanoBackupJob, transfer: JsonObject) {
        val progress = transfer.getValue("progress") as? JsonObject

        job.percent = (progress?.getValue("percent") as? Number)?.toInt()?.coerceIn(0, 100)
        job.remotePhase = progress?.getString("phase")
    }

    // MC server backups

    /**
     * Uploads one managed MC server backup: a full zip is wrapped (+ encrypted, with a saved passphrase) as `kind: mc-server`,
     * a snapshot goes into the account's snapshot repository with the server as its subject ([startMcSnapshotUpload]).
     */
    fun startMcUpload(source: McServerBackupSource): PanoBackupJob {
        if (source.isSnapshot) return startMcSnapshotUpload(source)

        val zip = tempFile("mc-zip")
        val temp = tempFile("mc")

        return backups.startTask(PanoBackupJob.Type.MC_UPLOAD, cleanup = { delete(zip); delete(temp) }) { job ->
            requireConnected()

            val passphrase = passphraseFile?.read()
            val encrypted = passphrase != null && passphrase.isNotEmpty()

            try {
                job.phase = PanoBackupService.PHASE_FETCHING
                source.fetch(zip)

                job.phase = PanoBackupService.PHASE_ARCHIVING
                withContext(Dispatchers.IO) { McServerArchive.wrap(zip, source.meta, temp, passphrase, producer, instanceName()) }
            } finally {
                passphrase?.fill('\u0000')
            }

            delete(zip)

            job.phase = PanoBackupService.PHASE_UPLOADING
            job.bytesTotal = temp.length()

            val summary = JsonObject()
                .put("serverId", source.serverId)
                .put("serverUuid", source.subject)
                .put("serverName", source.meta.getString("serverName"))
                .put("backupId", source.backupId)
                .put("backupName", source.meta.getString("name"))
                .put("encrypted", encrypted)

            val uploaded = try {
                target.put(
                    temp,
                    StoredArchive(KIND_MC_SERVER, source.subject, encrypted = encrypted, summary = summary),
                    onStarted = { job.remoteId = it }
                ) { job.bytesDone = it }.also { job.remoteId = it }
            } finally {
                listCache = null
            }

            recordMcUpload(source, uploaded)

            // The upload stands even when the pruning fails: the next upload of this server tries again.
            try {
                pruneMc(source, uploaded)
            } catch (e: Exception) {
                logger.warn("Could not apply the Pano Backup retention of server ${source.serverId}: ${e.message}")
            }
        }
    }

    /**
     * A server snapshot to Pano Backup: the node's snapshot (streamed to Pano as a zip) is cut into the account's snapshot
     * repository under `server/`, deduplicated against every snapshot of this Pano, with the server's uuid as its subject.
     * Snapshots are sealed with the repository key like the Pano's own (not with the passphrase).
     */
    private fun startMcSnapshotUpload(source: McServerBackupSource): PanoBackupJob {
        val zip = tempFile("mc-snapshot-zip")
        val dir = workDir("mc-snapshot")

        return backups.startTask(PanoBackupJob.Type.MC_UPLOAD, cleanup = { delete(zip); deleteTree(dir) }) { job ->
            requireConnected()

            job.phase = PanoBackupService.PHASE_FETCHING
            source.fetch(zip)

            job.phase = PanoBackupService.PHASE_UPLOADING

            val name = (source.meta.getString("name") ?: "").take(SNAPSHOT_NAME_LENGTH).ifEmpty { null }
            val session = try {
                client.startSnapshot(name, source.subject)
            } finally {
                listCache = null
            }

            job.remoteId = session.id

            val meta = source.meta.copy().put("serverId", source.serverId).put("serverUuid", source.subject)
            val stats = try {
                withContext(Dispatchers.IO) {
                    McServerSnapshotUpload(client, session, zip, meta, dir, name ?: "", clock()) { done, total ->
                        job.bytesDone = done
                        job.bytesTotal = total
                    }.run()
                }.stats
            } catch (e: Throwable) {
                withContext(NonCancellable) { runCatching { client.deleteSnapshot(session.id) } }

                throw e
            }

            try {
                val settled = client.completeSnapshot(session.id, stats)

                if (settled.getString("status") != SNAPSHOT_DONE) {
                    throw PanoHostException(
                        SNAPSHOT_FAILED,
                        message = settled.getString("error") ?: "Pano Backup could not settle the snapshot.",
                        extras = JsonObject().put("snapshotId", session.id).put("error", settled.getString("error")).put("status", settled.getString("status"))
                    )
                }
            } catch (e: Throwable) {
                withContext(NonCancellable) { runCatching { client.deleteSnapshot(session.id) } }

                throw e
            } finally {
                listCache = null
            }

            recordMcUpload(source, session.id)

            try {
                pruneMc(source, session.id)
            } catch (e: Exception) {
                logger.warn("Could not apply the Pano Backup retention of server ${source.serverId}: ${e.message}")
            }
        }
    }

    /** Tells [source] where its copy went; the upload stands when that fails (the backup only shows no cloud mark). */
    private suspend fun recordMcUpload(source: McServerBackupSource, remoteId: String) {
        try {
            source.onUploaded(remoteId)
        } catch (e: Exception) {
            logger.warn("Could not record the Pano Backup copy of backup ${source.backupId}: ${e.message}")
        }
    }

    /**
     * This Pano's `mc-server` copies of [subject] at Pano Backup, newest first: its archives (`type: archive`) and its
     * snapshots (`type: snapshot`). Only the ones this Pano uploaded: another Pano's copies of a server with the same
     * uuid are that Pano's to keep.
     */
    suspend fun listMcBackups(subject: String): List<JsonObject> {
        val list = listBackups()
        val archives = (list.getJsonArray("panos") ?: JsonArray()).filterIsInstance<JsonObject>()
            .flatMap { pano -> (pano.getJsonArray("backups") ?: JsonArray()).filterIsInstance<JsonObject>() }
            .filter { it.getString("kind") == KIND_MC_SERVER && it.getString("subject") == subject && it.getBoolean("own") == true }
            .distinctBy { it.getString("id") }
            .map { it.copy().put("type", TYPE_ARCHIVE) }
        val snapshots = (client.listSnapshots().getJsonArray("snapshots") ?: JsonArray()).filterIsInstance<JsonObject>()
            .filter { it.getString("subject") == subject && it.getBoolean("own") != false }
            .map { it.copy().put("type", TYPE_SNAPSHOT) }

        return (archives + snapshots).sortedByDescending { it.getLong("createdAt") ?: 0L }
    }

    /** Deletes what the server's retention says is surplus at Pano Backup among the copies of [source]'s kind, never [uploaded]. */
    private suspend fun pruneMc(source: McServerBackupSource, uploaded: String) {
        val type = if (source.isSnapshot) TYPE_SNAPSHOT else TYPE_ARCHIVE
        val rows = listMcBackups(source.subject)
            .filter { it.getString("type") == type && it.getString("status") == DONE }
            .map { PanoBackupRetentionRule.Row(it.getString("id"), it.getLong("createdAt") ?: 0L) }

        PanoBackupRetentionRule.selectForDeletion(rows, source.settings, clock(), uploaded).forEach { row ->
            if (source.isSnapshot) client.deleteSnapshot(row.id) else client.deleteBackup(row.id)
            logger.info("Pano Backup retention of server ${source.serverId}: deleted ${row.id}")
        }

        listCache = null
    }

    /**
     * Brings `mc-server` backup [id] (any backup of the account) into managed server [serverId]'s own backups:
     * download → verify → open with [passphrase] (null = the saved one) → handed to [importer], which pushes the zip to
     * the server's node. Nothing on the server is overwritten; the admin restores it from the server's Backups page.
     */
    fun startMcImport(id: String, passphrase: CharArray?, serverId: Long, importer: McServerBackupImporter, snapshot: Boolean = false): PanoBackupJob {
        val temp = tempFile("mc-import")
        val dir = workDir("mc-import")
        val key = passphrase ?: passphraseFile?.read()

        return backups.startTask(PanoBackupJob.Type.MC_IMPORT, cleanup = { key?.fill('\u0000'); delete(temp); deleteTree(dir) }) { job ->
            requireConnected()

            job.remoteId = id
            job.phase = PanoBackupService.PHASE_DOWNLOADING

            if (snapshot) {
                // A snapshot: its chunks are read and checked, and the zip the node had is rebuilt from them.
                val session = client.openSnapshot(id)
                val zip = File(dir, "backup.zip")
                val meta = withContext(Dispatchers.IO) {
                    SnapshotRestoreArchive(client, session, dir, McServerSnapshot.SCOPE) { done, total ->
                        job.bytesDone = done
                        job.bytesTotal = total
                    }.buildServerZip(zip)
                }

                job.phase = PHASE_IMPORTING
                job.bytesDone = 0
                job.bytesTotal = zip.length()
                job.backupId = importer.import(serverId, zip, meta) { job.bytesDone = it }

                return@startTask
            }

            target.fetch(id, temp) { job.bytesDone = it }

            job.phase = PanoBackupService.PHASE_VERIFYING
            job.bytesDone = 0
            job.bytesTotal = 0

            val (zip, meta) = withContext(Dispatchers.IO) { McServerArchive.open(temp, key, dir) }

            delete(temp)

            job.phase = PHASE_IMPORTING
            job.bytesTotal = zip.length()
            job.backupId = importer.import(serverId, zip, meta) { job.bytesDone = it }
        }
    }

    /**
     * A managed MC server finished a backup: uploaded when the server is selected in the settings
     * (queued while another job runs; the tick retries).
     */
    suspend fun onMcBackupReady(source: McServerBackupSource) {
        if (!isConnected()) {
            return
        }

        if (!tryStart { startMcUpload(source) }) {
            if (mcQueue.size < MAX_MC_QUEUE) mcQueue.add(source)
        }
    }

    // Schedule

    /**
     * Scheduled run (a snapshot or a full archive, per [RemoteBackupSettings.mode]) when due and the account has a plan
     * (checked on the cached overview, so a missing plan never reads the whole Pano every tick), then one queued MC
     * upload. A failed scheduled run is retried after [RETRY_AFTER_FAILURE_MS]; one the storage refused (full, no plan)
     * waits for the next scheduled slot, or for a change of plan / free space seen in the overview.
     */
    suspend fun tick(now: Long = clock()) {
        download?.takeIf { it.expiresAt <= now }?.let { discardDownload(it.jobId) }

        settleScheduled()

        val state = state()

        if (!isConnected()) {
            return
        }

        val lastFailure = lastScheduledFailureAt

        if (scheduledDue(state, now) && (lastFailure == null || now - lastFailure >= RETRY_AFTER_FAILURE_MS)) {
            // A paid plan, or the free quota every account has (`usage.quotaBytes` is null without either).
            val list = try {
                cachedList(fresh = false)
            } catch (e: PanoHostException) {
                logger.warn("Could not read the Pano Backup plan: ${e.code}")
                null
            }
            val allowed = list != null && (planOf(list) != null || list.getJsonObject("usage")?.getValue("quotaBytes") != null)

            if (!allowed) {
                logger.warn("Pano Backup is scheduled but the connected panomc.com account has no active Pano Backup plan or free quota")
                lastScheduledFailureAt = now
            } else if (state.settings.mode == RemoteBackupSettings.Mode.FULL && !fitsLastArchive(state, list!!)) {
                // The archive is not even started: it would be thrown away at the quota.
                logger.warn("Pano Backup storage is full: the scheduled full archive (${state.lastFullBytes} bytes last time) is skipped until the next slot")
                storageRefusal = StorageRefusal.of(now, list)

                try {
                    onScheduledSettled(false)
                } catch (e: Exception) {
                    logger.warn("Could not record the skipped Pano Backup upload: ${e.message}")
                }
            } else {
                logger.info("Taking the scheduled Pano Backup ${state.settings.mode.name.lowercase()}")

                tryStart {
                    when (state.settings.mode) {
                        RemoteBackupSettings.Mode.SNAPSHOT -> startSnapshot(SNAPSHOT_SCHEDULED)
                        RemoteBackupSettings.Mode.FULL -> startUpload(scheduled = true)
                    }.also { watchScheduled(it, now, list) }
                }
            }
        }

        mcQueue.peek()?.let { next -> if (tryStart { startMcUpload(next) }) mcQueue.remove(next) }
    }

    /**
     * Whether a scheduled run is due: the schedule says so and no refusal of the storage holds it back. A refusal counts
     * as an attempt (so the next one is the next slot) and is lifted early when the overview shows another plan or more
     * free bytes than when it happened.
     */
    private suspend fun scheduledDue(state: RemoteBackupState, now: Long): Boolean {
        if (!state.settings.isDue(state.lastUploadAt, now)) {
            return false
        }

        val refusal = storageRefusal ?: return true
        val list = try {
            cachedList(fresh = false)
        } catch (_: PanoHostException) {
            null
        }

        if (list != null && refusal.changedBy(list)) {
            storageRefusal = null

            return true
        }

        return state.settings.isDue(refusal.at, now)
    }

    /** Whether the free bytes of [list] can take the last full archive (true when either is unknown). */
    private fun fitsLastArchive(state: RemoteBackupState, list: JsonObject): Boolean {
        val need = state.lastFullBytes ?: return true
        val free = (list.getJsonObject("usage")?.getValue("freeBytes") as? Number)?.toLong() ?: return true

        return free >= need
    }

    private fun StorageRefusal.changedBy(list: JsonObject): Boolean {
        val tier = list.getJsonObject("tier")?.getString("id")
        val free = (list.getJsonObject("usage")?.getValue("freeBytes") as? Number)?.toLong()

        return tier != tierId || (free != null && (freeBytes == null || free > freeBytes))
    }

    /** Remembers the scheduled upload; [settleScheduled] records a failure once its job ends. */
    private fun watchScheduled(job: PanoBackupJob, startedAt: Long, overview: JsonObject?) {
        scheduledJob = job
        scheduledAt = startedAt
        scheduledOverview = overview
    }

    /** The overview the scheduled run started on (its plan and free bytes, for a refusal). */
    @Volatile
    private var scheduledOverview: JsonObject? = null

    @Volatile
    private var scheduledJob: PanoBackupJob? = null

    @Volatile
    private var scheduledAt: Long = 0

    /** Records the outcome of the last scheduled upload (called at the start of [tick]). */
    private suspend fun settleScheduled() {
        val job = scheduledJob ?: return

        if (job.status == PanoBackupJob.Status.RUNNING) return

        if (job.status == PanoBackupJob.Status.FAILED) {
            logger.warn("The scheduled Pano Backup upload failed: ${job.error}")

            if (refusedByStorage(job)) {
                storageRefusal = StorageRefusal.of(scheduledAt, scheduledOverview)
            } else {
                lastScheduledFailureAt = scheduledAt
            }
        }

        scheduledJob = null

        try {
            onScheduledSettled(job.status == PanoBackupJob.Status.DONE)
        } catch (e: Exception) {
            logger.warn("Could not record the scheduled Pano Backup upload: ${e.message}")
        }
    }

    /** No plan, or the quota is used up: retrying within the slot cannot work and would read the whole Pano each time. */
    private fun refusedByStorage(job: PanoBackupJob): Boolean = when (job.error) {
        "PAYMENT_REQUIRED" -> true
        "QUOTA_EXCEEDED" -> job.details?.getString("reason").let { it == null || it == "QUOTA" }
        else -> false
    }

    private fun tryStart(start: () -> PanoBackupJob): Boolean = try {
        start()
        true
    } catch (e: PanoBackupException) {
        if (e.code != PanoBackupService.BUSY) throw e
        false
    }

    // Helpers

    private fun tempFile(what: String): File {
        val dir = tempDir()

        dir.mkdirs()

        return File(dir, "pano-backup-$what-${UUID.randomUUID()}.panoarc")
    }

    /** A scratch folder for a snapshot job, next to the temp archives. */
    private fun workDir(what: String): File {
        val dir = tempDir()

        dir.mkdirs()

        return File(dir, "pano-backup-$what-${UUID.randomUUID()}")
    }

    private suspend fun delete(file: File) {
        withContext(Dispatchers.IO) { file.delete() }
    }

    private suspend fun deleteTree(dir: File) {
        withContext(NonCancellable + Dispatchers.IO) { dir.deleteRecursively() }
    }

    companion object {
        const val KIND_MC_SERVER = "mc-server"

        /** A finished archive at Pano Backup. */
        const val DONE = "DONE"

        /** What a server copy at Pano Backup is in [listMcBackups]: an `mc-server` archive or a server snapshot. */
        const val TYPE_ARCHIVE = "archive"
        const val TYPE_SNAPSHOT = "snapshot"

        /** The longest name a snapshot may carry at Pano Backup. */
        const val SNAPSHOT_NAME_LENGTH = 100

        /** An `mc-server` backup is being handed to the server's node ([startMcImport]). */
        const val PHASE_IMPORTING = "IMPORTING"

        const val PASSPHRASE_NOT_SET = "PASSPHRASE_NOT_SET"

        /** A snapshot Pano Host could not settle (`error` = its reason: `PACK_SIZE_MISMATCH`, `INTEGRITY_FAILED`, `MISSING_CHUNKS`, …). */
        const val SNAPSHOT_FAILED = "SNAPSHOT_FAILED"
        private const val SNAPSHOT_DONE = "DONE"
        private const val SNAPSHOT_MANUAL = "Manual"
        private const val SNAPSHOT_SCHEDULED = "Scheduled"

        /** `trigger` of an archive's summary: how the upload was started. */
        const val TRIGGER_MANUAL = "MANUAL"
        const val TRIGGER_SCHEDULED = "SCHEDULED"

        /** A move out of Pano Host: its export failed, vanished or took too long. */
        const val EXPORT_FAILED_CODE = "EXPORT_FAILED"
        const val PHASE_EXPORTING = "EXPORTING"
        private const val EXPORT_DONE = "DONE"
        private const val EXPORT_FAILED = "FAILED"
        /** How long a built snapshot download waits to be fetched. */
        const val DOWNLOAD_TTL_MS = 30L * 60 * 1000

        const val EXPORT_POLL_MS = 5_000L

        const val PHASE_TRANSFER_RESTORING = "TRANSFER_RESTORING"
        const val TRANSFER_FAILED_CODE = "TRANSFER_FAILED"
        const val TRANSFER_TIMEOUT_CODE = "TRANSFER_TIMEOUT"
        private const val TRANSFER_DONE = "DONE"
        private val TRANSFER_ENDED = setOf("FAILED", "CANCELED", "EXPIRED")
        const val TRANSFER_POLL_MS = 5_000L
        const val TRANSFER_TIMEOUT_MS = 2L * 60 * 60 * 1000
        const val EXPORT_TIMEOUT_MS = 60L * 60 * 1000
        const val NOT_AVAILABLE = "NOT_AVAILABLE"
        const val WORKLOAD_NOT_FOUND = "WORKLOAD_NOT_FOUND"

        private const val MAX_MC_QUEUE = 20
        private const val LIST_CACHE_MS = 60L * 1000
        const val RETRY_AFTER_FAILURE_MS = 60L * 60 * 1000

        /** Pano Backup ids are UUIDs; anything else never reaches a URL. */
        fun isValidRemoteId(id: String?) = id != null && runCatching { UUID.fromString(id).toString() == id.lowercase() }.getOrDefault(false)
    }
}
