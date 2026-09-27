package com.panomc.platform.backup.remote

import com.panomc.platform.backup.PanoBackupException
import com.panomc.platform.backup.PanoBackupJob
import com.panomc.platform.backup.PanoBackupRunner
import com.panomc.platform.backup.PanoBackupService
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import kotlinx.coroutines.Dispatchers
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
 * - upload: passphrase-E2E archive to a temp file → [PanoBackupTarget] (presigned multipart with
 *   retry, stop polled between parts → complete); restore: download → verify → the local restore
 *   flow of [backups];
 * - schedule per [RemoteBackupSettings] (the plan has no frequency rule; a failed scheduled upload
 *   waits [RETRY_AFTER_FAILURE_MS] before the next attempt);
 * - transfer: a plain archive pushed into one of the account's Pano workloads (Pano Host asks the
 *   owner to confirm before restoring it);
 * - managed MC server backups wrapped as `kind: mc-server`, encrypted and uploaded.
 *
 * Every archive/restore/upload runs as the one [PanoBackupService] job (single flight). Not
 * connected → [PanoHostException.CONNECT_REQUIRED].
 */
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
    private val logger: Logger = LoggerFactory.getLogger(PanoRemoteBackupService::class.java)
) {
    private val stateLock = Mutex()
    private val mcQueue = ConcurrentLinkedQueue<McServerBackupSource>()
    private val target = PanoBackupTarget(client)

    /** The last `GET /host/connected/backups` answer and when (plan + usage for the overview and the schedule). */
    @Volatile
    private var listCache: Pair<Long, JsonObject>? = null

    @Volatile
    private var lastScheduledFailureAt: Long? = null

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

    /** Archives this Pano with the saved passphrase and uploads it; throws BUSY when a job runs. */
    fun startUpload(): PanoBackupJob {
        val temp = tempFile("upload")

        return backups.startTask(PanoBackupJob.Type.UPLOAD, cleanup = { delete(temp) }) { job -> upload(job, temp) }
    }

    private suspend fun upload(job: PanoBackupJob, temp: File) {
        requireConnected()

        val passphrase = passphraseFile?.read() ?: throw PanoHostException(PASSPHRASE_NOT_SET)

        try {
            job.phase = PanoBackupService.PHASE_ARCHIVING
            backups.archiveTo(temp, passphrase)
        } finally {
            passphrase.fill('\u0000')
        }

        job.phase = PanoBackupService.PHASE_UPLOADING
        job.bytesTotal = temp.length()

        val summary = JsonObject()
            .put("panoVersion", panoVersion)
            .put("encrypted", true)

        try {
            job.remoteId = target.put(
                temp,
                StoredArchive(LocalBackupTarget.KIND_PANO_INSTANCE, null, encrypted = true, summary = summary),
                onStarted = { job.remoteId = it }
            ) { job.bytesDone = it }
        } finally {
            listCache = null
        }

        update { it.copy(lastUploadAt = clock()) }
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

    suspend fun cancelTransfer(id: String) {
        requireConnected()
        client.cancelTransfer(id)
    }

    /**
     * Pushes a plain archive of this Pano into workload [workloadId]. The job ends when the upload is
     * complete (`AWAITING_CONFIRMATION`); the owner confirms on panomc.com, then poll [getTransfer].
     */
    fun startTransfer(workloadId: String): PanoBackupJob {
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

            val session = client.startTransfer(workloadId, temp.length())

            job.remoteId = session.id

            try {
                client.uploadParts(temp, session) { job.bytesDone = it }
                client.completeTransfer(session.id)
            } catch (e: Throwable) {
                runCatching { client.cancelTransfer(session.id) }

                throw e
            }
        }
    }

    // MC server backups

    /** Wraps + encrypts + uploads one managed MC server backup (`kind: mc-server`). */
    fun startMcUpload(source: McServerBackupSource): PanoBackupJob {
        val zip = tempFile("mc-zip")
        val temp = tempFile("mc")

        return backups.startTask(PanoBackupJob.Type.MC_UPLOAD, cleanup = { delete(zip); delete(temp) }) { job ->
            requireConnected()

            val passphrase = passphraseFile?.read() ?: throw PanoHostException(PASSPHRASE_NOT_SET)

            try {
                job.phase = PanoBackupService.PHASE_FETCHING
                source.fetch(zip)

                job.phase = PanoBackupService.PHASE_ARCHIVING
                withContext(Dispatchers.IO) { McServerArchive.wrap(zip, source.meta, temp, passphrase, producer, instanceName()) }
            } finally {
                passphrase.fill('\u0000')
            }

            delete(zip)

            job.phase = PanoBackupService.PHASE_UPLOADING
            job.bytesTotal = temp.length()

            val summary = JsonObject().put("serverId", source.serverId).put("backupId", source.backupId).put("encrypted", true)

            try {
                job.remoteId = target.put(
                    temp,
                    StoredArchive(KIND_MC_SERVER, source.subject, encrypted = true, summary = summary),
                    onStarted = { job.remoteId = it }
                ) { job.bytesDone = it }
            } finally {
                listCache = null
            }
        }
    }

    /**
     * A managed MC server finished a backup: uploaded when the server is selected in the settings
     * (queued while another job runs; the tick retries).
     */
    suspend fun onMcBackupReady(source: McServerBackupSource) {
        val state = state()

        if (source.serverId !in state.settings.mcServerIds || !isConnected() || passphraseFile?.isSet() != true) {
            return
        }

        if (!tryStart { startMcUpload(source) }) {
            if (mcQueue.size < MAX_MC_QUEUE) mcQueue.add(source)
        }
    }

    // Schedule

    /**
     * Scheduled upload when due and the account has a plan (checked on the cached overview, so a
     * missing plan never archives the whole Pano every tick), then one queued MC upload. A failed
     * scheduled upload is retried after [RETRY_AFTER_FAILURE_MS].
     */
    suspend fun tick(now: Long = clock()) {
        settleScheduled()

        val state = state()

        if (!isConnected() || passphraseFile?.isSet() != true) {
            return
        }

        val lastFailure = lastScheduledFailureAt

        if (state.settings.isDue(state.lastUploadAt, now) && (lastFailure == null || now - lastFailure >= RETRY_AFTER_FAILURE_MS)) {
            val plan = try {
                planOf(cachedList(fresh = false))
            } catch (e: PanoHostException) {
                logger.warn("Could not read the Pano Backup plan: ${e.code}")
                null
            }

            if (plan == null) {
                logger.warn("Pano Backup is scheduled but the connected panomc.com account has no active Pano Backup plan")
                lastScheduledFailureAt = now
            } else {
                logger.info("Taking the scheduled Pano Backup upload")

                tryStart { startUpload().also { watchScheduled(it, now) } }
            }
        }

        mcQueue.peek()?.let { next -> if (tryStart { startMcUpload(next) }) mcQueue.remove(next) }
    }

    /** Remembers the scheduled upload; [settleScheduled] records a failure once its job ends. */
    private fun watchScheduled(job: PanoBackupJob, startedAt: Long) {
        scheduledJob = job
        scheduledAt = startedAt
    }

    @Volatile
    private var scheduledJob: PanoBackupJob? = null

    @Volatile
    private var scheduledAt: Long = 0

    /** Records the outcome of the last scheduled upload (called at the start of [tick]). */
    private fun settleScheduled() {
        val job = scheduledJob ?: return

        if (job.status == PanoBackupJob.Status.RUNNING) return

        if (job.status == PanoBackupJob.Status.FAILED) {
            logger.warn("The scheduled Pano Backup upload failed: ${job.error}")
            lastScheduledFailureAt = scheduledAt
        }

        scheduledJob = null
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

    private suspend fun delete(file: File) {
        withContext(Dispatchers.IO) { file.delete() }
    }

    companion object {
        const val KIND_MC_SERVER = "mc-server"

        const val PASSPHRASE_NOT_SET = "PASSPHRASE_NOT_SET"
        const val NOT_AVAILABLE = "NOT_AVAILABLE"
        const val WORKLOAD_NOT_FOUND = "WORKLOAD_NOT_FOUND"

        private const val MAX_MC_QUEUE = 20
        private const val LIST_CACHE_MS = 60L * 1000
        const val RETRY_AFTER_FAILURE_MS = 60L * 60 * 1000

        /** Pano Backup ids are UUIDs; anything else never reaches a URL. */
        fun isValidRemoteId(id: String?) = id != null && runCatching { UUID.fromString(id).toString() == id.lowercase() }.getOrDefault(false)
    }
}
