package com.panomc.platform.backup.remote

import com.panomc.platform.backup.PanoBackupException
import com.panomc.platform.backup.PanoBackupJob
import com.panomc.platform.backup.PanoBackupRunner
import com.panomc.platform.backup.PanoBackupService
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

/**
 * Pano Backup + transfer for a self-hosted Pano (the platform side of host-api.md §Link):
 * - link: device-code flow (`start` → approve on panomc.com → `poll`), the token is kept in
 *   [stateStore] (the database), pending requests in memory;
 * - upload: passphrase-E2E archive to a temp file → [PanoBackupTarget] (presigned multipart with
 *   retry → complete); restore: download → verify → the local restore flow of [backups];
 * - schedule per [RemoteBackupSettings] and the tier's minimum interval;
 * - transfer: a plain archive pushed into the one workload a `TRANSFER` link is bound to (Pano
 *   Host asks the owner to confirm before restoring it);
 * - managed MC server backups wrapped as `kind: mc-server`, encrypted and uploaded.
 *
 * Every archive/restore/upload runs as the one [PanoBackupService] job (single flight).
 */
class PanoRemoteBackupService(
    private val client: PanoHostClient,
    private val stateStore: RemoteStateStore,
    /** Null in setup mode: nothing is uploaded there. */
    private val passphraseFile: PassphraseFile?,
    private val backups: PanoBackupRunner,
    private val tempDir: () -> File,
    private val instanceName: () -> String,
    private val panoVersion: String,
    private val clock: () -> Long = System::currentTimeMillis,
    private val logger: Logger = LoggerFactory.getLogger(PanoRemoteBackupService::class.java)
) {
    private val pending = ConcurrentHashMap<LinkPurpose, PendingLink>()
    private val stateLock = Mutex()
    private val mcQueue = ConcurrentLinkedQueue<McServerBackupSource>()

    @Volatile
    private var tierCache: Pair<Long, JsonObject?>? = null

    val producer: String get() = "pano/$panoVersion"

    // State

    suspend fun state(): RemoteBackupState = stateStore.load()

    private suspend fun update(change: (RemoteBackupState) -> RemoteBackupState): RemoteBackupState = stateLock.withLock {
        change(stateStore.load()).also { stateStore.save(it) }
    }

    /** Overview for the UI (never the token or the passphrase). */
    suspend fun status(): JsonObject {
        val state = state()
        val now = clock()

        return JsonObject()
            .put("links", JsonObject().also { json -> state.links.forEach { (purpose, link) -> json.put(purpose.name, link.toPublicJson()) } })
            .put("pending", JsonObject().also { json ->
                pending.values.filter { it.expiresAt > now }.forEach { json.put(it.purpose.name, it.toPublicJson()) }
            })
            .put("settings", state.settings.toJson())
            .put("passphraseSet", passphraseFile?.isSet() == true)
            .put("lastUploadAt", state.lastUploadAt)
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

    // Link flow

    suspend fun startLink(purpose: LinkPurpose): PendingLink {
        val name = instanceName().trim().take(64).ifEmpty { "Pano" }
        val response = client.linkStart(name, purpose)
        val link = PendingLink(
            purpose = purpose,
            pollToken = response.getString("pollToken") ?: throw PanoHostException(PanoHostException.UNAVAILABLE),
            code = response.getString("code", ""),
            verifyUrl = response.getString("verifyUrl", ""),
            instanceName = name,
            expiresAt = clock() + response.getLong("expiresIn", 600L) * 1000,
            intervalSeconds = response.getInteger("interval", 5)
        )

        pending[purpose] = link

        return link
    }

    /** `{status: NONE|PENDING|LINKED|EXPIRED, link?}`; stores the token once approved. */
    suspend fun pollLink(purpose: LinkPurpose): JsonObject {
        val request = pending[purpose]

        if (request == null) {
            val link = state().links[purpose]

            return JsonObject().put("status", if (link != null) LINKED else NONE).put("link", link?.toPublicJson())
        }

        if (request.expiresAt <= clock()) {
            pending.remove(purpose, request)

            return JsonObject().put("status", EXPIRED)
        }

        val response = try {
            client.linkPoll(request.pollToken)
        } catch (e: PanoHostException) {
            if (e.code == LINK_NOT_FOUND) {
                pending.remove(purpose, request)

                return JsonObject().put("status", EXPIRED)
            }

            throw e
        }

        val token = response.getString("token")

        if (response.getString("status") != "ACTIVE" || token.isNullOrBlank()) {
            return JsonObject().put("status", PENDING).put("pending", request.toPublicJson())
        }

        val link = HostLinkState(
            purpose = purpose,
            token = token,
            linkId = response.getValue("linkId")?.toString(),
            instanceName = request.instanceName,
            workloadId = response.getString("workloadId"),
            linkedAt = clock()
        )

        update { it.copy(links = it.links + (purpose to link)) }
        pending.remove(purpose, request)
        tierCache = null

        return JsonObject().put("status", LINKED).put("link", link.toPublicJson())
    }

    /** Forgets the link here (revoking it for good happens on panomc.com, `/host/links`). */
    suspend fun unlink(purpose: LinkPurpose) {
        pending.remove(purpose)
        update { it.copy(links = it.links - purpose) }
        tierCache = null
    }

    private suspend fun link(purpose: LinkPurpose): HostLinkState =
        state().links[purpose] ?: throw PanoHostException(NOT_LINKED, extras = JsonObject().put("purpose", purpose.name))

    /** Runs [block] with the link's token; a revoked token (`INVALID_TOKEN`) drops the link here too. */
    private suspend fun <T> withLink(purpose: LinkPurpose, block: suspend (String) -> T): T {
        val link = link(purpose)

        try {
            return block(link.token)
        } catch (e: PanoHostException) {
            if (e.code == PanoHostException.INVALID_TOKEN) {
                logger.warn("The Pano Host ${purpose.name.lowercase()} link was revoked; forgetting it")
                update { state -> if (state.links[purpose]?.token == link.token) state.copy(links = state.links - purpose) else state }
            }

            throw e
        }
    }

    private fun target() = PanoBackupTarget(client) { link(LinkPurpose.BACKUP).token }

    // Pano Backup

    /** `{backups, tier, usage}` of the account (read-only for backups of other links). */
    suspend fun listBackups(): JsonObject = withLink(LinkPurpose.BACKUP) { token ->
        client.listBackups(token).also { tierCache = clock() to it.getJsonObject("tier") }
    }

    suspend fun deleteBackup(id: String) = withLink(LinkPurpose.BACKUP) { token -> client.deleteBackup(token, id) }

    /** Archives this Pano with the saved passphrase and uploads it; throws BUSY when a job runs. */
    fun startUpload(): PanoBackupJob {
        val temp = tempFile("upload")

        return backups.startTask(PanoBackupJob.Type.UPLOAD, cleanup = { delete(temp) }) { job -> upload(job, temp) }
    }

    private suspend fun upload(job: PanoBackupJob, temp: File) {
        link(LinkPurpose.BACKUP)

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

        job.remoteId = withLink(LinkPurpose.BACKUP) {
            target().put(
                temp,
                StoredArchive(LocalBackupTarget.KIND_PANO_INSTANCE, null, encrypted = true, summary = summary),
                onStarted = { job.remoteId = it }
            ) { job.bytesDone = it }
        }

        update { it.copy(lastUploadAt = clock()) }
    }

    /**
     * Restores Pano Backup [id] over this Pano through [service] (the panel's, or setup mode's with
     * its own target database). [passphrase] null = the saved one.
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
            job.remoteId = id
            job.phase = PanoBackupService.PHASE_DOWNLOADING

            withLink(LinkPurpose.BACKUP) { target().fetch(id, temp) { job.bytesDone = it } }

            job.phase = PanoBackupService.PHASE_RESTORING
            job.backupId = service.restoreFrom(temp, key, safetyArchive, maintenance)?.id
        }
    }

    // Transfer

    /** `{workload, transfers}` of the workload the TRANSFER link is bound to. */
    suspend fun listTransfers(): JsonObject = withLink(LinkPurpose.TRANSFER) { client.listTransfers(it) }

    suspend fun getTransfer(id: String): JsonObject = withLink(LinkPurpose.TRANSFER) { client.getTransfer(it, id) }

    suspend fun cancelTransfer(id: String) = withLink(LinkPurpose.TRANSFER) { client.cancelTransfer(it, id) }

    /**
     * Pushes a plain archive of this Pano into the linked workload. The job ends when the upload is
     * complete (`AWAITING_CONFIRMATION`); the owner confirms on panomc.com, then poll [getTransfer].
     */
    fun startTransfer(): PanoBackupJob {
        val temp = tempFile("transfer")

        return backups.startTask(PanoBackupJob.Type.TRANSFER, cleanup = { delete(temp) }) { job ->
            link(LinkPurpose.TRANSFER)

            job.phase = PanoBackupService.PHASE_ARCHIVING
            backups.archiveTo(temp, null)

            job.phase = PanoBackupService.PHASE_UPLOADING
            job.bytesTotal = temp.length()

            withLink(LinkPurpose.TRANSFER) { token ->
                val session = client.startTransfer(token, temp.length())

                job.remoteId = session.id

                try {
                    client.uploadParts(temp, session) { job.bytesDone = it }
                    client.completeTransfer(token, session.id)
                } catch (e: Throwable) {
                    runCatching { client.cancelTransfer(token, session.id) }

                    throw e
                }
            }
        }
    }

    // MC server backups

    /** Wraps + encrypts + uploads one managed MC server backup (`kind: mc-server`). */
    fun startMcUpload(source: McServerBackupSource): PanoBackupJob {
        val zip = tempFile("mc-zip")
        val temp = tempFile("mc")

        return backups.startTask(PanoBackupJob.Type.MC_UPLOAD, cleanup = { delete(zip); delete(temp) }) { job ->
            link(LinkPurpose.BACKUP)

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

            job.remoteId = withLink(LinkPurpose.BACKUP) {
                target().put(
                temp,
                StoredArchive(KIND_MC_SERVER, source.subject, encrypted = true, summary = summary),
                onStarted = { job.remoteId = it }
            ) { job.bytesDone = it }
            }
        }
    }

    /**
     * A managed MC server finished a backup: uploaded when the server is selected in the settings
     * (queued while another job runs; the tick retries).
     */
    suspend fun onMcBackupReady(source: McServerBackupSource) {
        val state = state()

        if (source.serverId !in state.settings.mcServerIds || state.links[LinkPurpose.BACKUP] == null || passphraseFile?.isSet() != true) {
            return
        }

        if (!tryStart { startMcUpload(source) }) {
            if (mcQueue.size < MAX_MC_QUEUE) mcQueue.add(source)
        }
    }

    // Schedule

    /** Scheduled upload when due (settings + the tier's minimum interval), then one queued MC upload. */
    suspend fun tick(now: Long = clock()) {
        val state = state()
        val settings = state.settings

        if (state.links[LinkPurpose.BACKUP] == null || passphraseFile?.isSet() != true) {
            return
        }

        if (settings.isDue(state.lastUploadAt, null, now)) {
            val tier = tier(now)

            if (tier == null) {
                logger.warn("Pano Backup is scheduled but this panomc.com account has no Pano Backup subscription")
            } else if (settings.isDue(state.lastUploadAt, tier.getLong("minIntervalMinutes", 0L), now)) {
                logger.info("Taking the scheduled Pano Backup upload")

                tryStart { startUpload() }
            }
        }

        mcQueue.peek()?.let { next -> if (tryStart { startMcUpload(next) }) mcQueue.remove(next) }
    }

    private suspend fun tier(now: Long): JsonObject? {
        tierCache?.takeIf { now - it.first < TIER_CACHE_MS }?.let { return it.second }

        return try {
            listBackups().getJsonObject("tier")
        } catch (e: PanoHostException) {
            logger.warn("Could not read the Pano Backup tier: ${e.code}")
            null
        }
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

        const val NONE = "NONE"
        const val PENDING = "PENDING"
        const val LINKED = "LINKED"
        const val EXPIRED = "EXPIRED"

        const val NOT_LINKED = "PANO_HOST_NOT_LINKED"
        const val PASSPHRASE_NOT_SET = "PASSPHRASE_NOT_SET"
        const val NOT_AVAILABLE = "NOT_AVAILABLE"
        const val LINK_NOT_FOUND = "LINK_NOT_FOUND"

        private const val MAX_MC_QUEUE = 20
        private const val TIER_CACHE_MS = 60L * 60 * 1000

        /** Pano Backup ids are UUIDs; anything else never reaches a URL. */
        fun isValidRemoteId(id: String?) = id != null && runCatching { UUID.fromString(id).toString() == id.lowercase() }.getOrDefault(false)
    }
}
