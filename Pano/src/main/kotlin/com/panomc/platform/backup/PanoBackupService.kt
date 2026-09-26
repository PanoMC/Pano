package com.panomc.platform.backup

import com.panomc.platform.archive.ArchiveLimits
import com.panomc.platform.archive.ArchiveManifest
import com.panomc.platform.archive.ArchiveSource
import com.panomc.platform.archive.PanoArcEncryption
import com.panomc.platform.archive.PanoArcException
import com.panomc.platform.archive.PanoArcKeys
import com.panomc.platform.archive.instance.InstanceArchiver
import com.panomc.platform.archive.instance.InstanceLayout
import com.panomc.platform.archive.instance.InstanceRestorer
import com.panomc.platform.backup.remote.PanoHostException
import io.vertx.core.json.JsonObject
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.sqlclient.SqlConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.File
import java.util.UUID

/** What the backup service needs from the Pano it runs in (the running panel, setup mode or a test). */
interface PanoBackupHost {
    val layout: InstanceLayout
    val panoVersion: String

    fun dbPrefix(): String

    /** The current config as JSON (DB credentials, server block, uploads folder, prefix). */
    fun targetConfig(): JsonObject

    /** Scheme versions this Pano knows (`core` + plugin ids); an archive above them is refused. */
    fun knownSchemeVersions(): Map<String, Int>

    fun configVersion(): Int?

    /** A dedicated connection; the caller closes it. */
    suspend fun connect(): SqlConnection

    /** Switches maintenance mode and returns the previous state. */
    suspend fun setMaintenance(enabled: Boolean): Boolean

    /** Takes the restored config into the running Pano (in memory + config.conf). */
    suspend fun applyConfig(config: JsonObject)

    /** Called once a restore has been applied: the platform must restart to load the restored state. */
    suspend fun restoreApplied()
}

/**
 * The one-at-a-time job runner Pano Backup builds on: [startTask] runs a background job while
 * holding the lock; inside it [archiveTo] / [restoreFrom] do the work.
 */
interface PanoBackupRunner {
    val job: PanoBackupJob?

    fun isBusy(): Boolean

    fun startTask(type: PanoBackupJob.Type, cleanup: suspend () -> Unit = {}, block: suspend (PanoBackupJob) -> Unit): PanoBackupJob

    suspend fun archiveTo(file: File, passphrase: CharArray?): ArchiveManifest

    suspend fun restoreFrom(source: File, passphrase: CharArray?, safetyArchive: Boolean, maintenance: Boolean): PanoBackupInfo?
}

class PanoBackupException(
    val code: String,
    message: String? = null,
    val rolledBack: Boolean = false,
    cause: Throwable? = null
) : Exception(message ?: code, cause)

data class PanoBackupJob(
    val id: String,
    val type: Type,
    @Volatile var status: Status = Status.RUNNING,
    val startedAt: Long = System.currentTimeMillis(),
    @Volatile var finishedAt: Long? = null,
    @Volatile var backupId: String? = null,
    @Volatile var error: String? = null,
    @Volatile var message: String? = null,
    @Volatile var rolledBack: Boolean = false,
    /** What the job is doing right now (`ARCHIVING`, `UPLOADING`, `DOWNLOADING`, `RESTORING`, …); UI hint only. */
    @Volatile var phase: String? = null,
    @Volatile var bytesDone: Long = 0,
    @Volatile var bytesTotal: Long = 0,
    /** Id on the remote side (Pano Backup backup id, transfer id) once known. */
    @Volatile var remoteId: String? = null,
    /** Extra fields of a Pano Host error (`reason`, `nextAllowedAt`, `quotaBytes`, …). */
    @Volatile var details: JsonObject? = null
) {
    enum class Type { CREATE, RESTORE, UPLOAD, TRANSFER, MC_UPLOAD }
    enum class Status { RUNNING, DONE, FAILED }

    fun toJson(): JsonObject = JsonObject()
        .put("id", id)
        .put("type", type.name)
        .put("status", status.name)
        .put("startedAt", startedAt)
        .put("finishedAt", finishedAt)
        .put("backupId", backupId)
        .put("error", error)
        .put("message", message)
        .put("rolledBack", rolledBack)
        .put("phase", phase)
        .put("bytesDone", bytesDone)
        .put("bytesTotal", bytesTotal)
        .put("remoteId", remoteId)
        .put("details", details)
}

/**
 * Local Pano backups: create (plain or passphrase E2E), and restore in the archive-format.md
 * section 5 order — stage + verify first (a rejected archive changes nothing), then maintenance
 * mode on, a pre-restore safety archive, drop + import + file swap + config, and a restart. A
 * failure after the safety archive was taken restores that safety archive (rollback).
 *
 * One operation at a time: a second create or restore while one runs is [BUSY].
 */
class PanoBackupService(
    val store: PanoBackupStore,
    private val host: PanoBackupHost,
    private val scope: CoroutineScope,
    private val limits: ArchiveLimits = ArchiveLimits(),
    private val logger: Logger = LoggerFactory.getLogger(PanoBackupService::class.java)
) : PanoBackupRunner {
    private val mutex = Mutex()

    @Volatile
    override var job: PanoBackupJob? = null
        private set

    /** Starts a backup in the background; throws [BUSY] when another operation runs. */
    fun startCreate(passphrase: CharArray?, tag: PanoBackupTag = PanoBackupTag.MANUAL, createdBy: String? = null): PanoBackupJob =
        startTask(PanoBackupJob.Type.CREATE, cleanup = { passphrase?.fill('\u0000') }) { job ->
            job.phase = PHASE_ARCHIVING
            job.backupId = createLocked(passphrase, tag, createdBy).id
        }

    /**
     * Starts a restore of [source] in the background. [deleteSource] removes an uploaded file once
     * done; [safetyArchive] + [maintenance] are off only in setup mode (nothing to protect yet).
     */
    fun startRestore(
        source: File,
        passphrase: CharArray?,
        deleteSource: Boolean,
        safetyArchive: Boolean = true,
        maintenance: Boolean = true
    ): PanoBackupJob = startTask(
        PanoBackupJob.Type.RESTORE,
        cleanup = {
            passphrase?.fill('\u0000')

            if (deleteSource) {
                withContext(Dispatchers.IO) { source.delete() }
            }
        }
    ) { job ->
        job.phase = PHASE_RESTORING
        job.backupId = restoreFrom(source, passphrase, safetyArchive, maintenance)?.id
    }

    /**
     * Runs [block] as the one background operation (throws [BUSY] when another runs); the job ends
     * DONE when it returns, FAILED with a stable code when it throws. [cleanup] always runs last.
     * Inside [block], [archiveTo] and [restoreFrom] may be called (the lock is held).
     */
    override fun startTask(
        type: PanoBackupJob.Type,
        cleanup: suspend () -> Unit,
        block: suspend (PanoBackupJob) -> Unit
    ): PanoBackupJob {
        val job = begin(type)

        scope.launch {
            var error: Throwable? = null

            try {
                block(job)
            } catch (e: Throwable) {
                error = e
            } finally {
                try {
                    cleanup()
                } catch (e: Throwable) {
                    logger.warn("Cleaning up after a Pano backup job failed: ${e.message}")
                }

                mutex.unlock()
            }

            // After the unlock, so a client that sees the job finished can start the next one.
            finish(job, error)
        }

        return job
    }

    /** Synchronous [startTask] (scheduler); null when another operation is running. */
    suspend fun <T> tryTask(block: suspend () -> T): T? {
        if (!mutex.tryLock()) {
            return null
        }

        try {
            return block()
        } finally {
            mutex.unlock()
        }
    }

    /** Synchronous create (scheduler, tests); null when another operation is running. */
    suspend fun tryCreate(passphrase: CharArray?, tag: PanoBackupTag, createdBy: String? = null): PanoBackupInfo? {
        if (!mutex.tryLock()) {
            return null
        }

        try {
            return createLocked(passphrase, tag, createdBy)
        } finally {
            mutex.unlock()
        }
    }

    /** Synchronous restore (tests); returns the safety backup when one was taken. */
    suspend fun restore(
        source: File,
        passphrase: CharArray?,
        safetyArchive: Boolean = true,
        maintenance: Boolean = true
    ): PanoBackupInfo? {
        if (!mutex.tryLock()) {
            throw PanoBackupException(BUSY)
        }

        try {
            return restoreFrom(source, passphrase, safetyArchive, maintenance)
        } finally {
            mutex.unlock()
        }
    }

    override fun isBusy() = mutex.isLocked

    private fun begin(type: PanoBackupJob.Type): PanoBackupJob {
        if (!mutex.tryLock()) {
            throw PanoBackupException(BUSY)
        }

        return PanoBackupJob(UUID.randomUUID().toString(), type).also { job = it }
    }

    private fun finish(job: PanoBackupJob, error: Throwable?) {
        job.finishedAt = System.currentTimeMillis()

        if (error == null) {
            job.status = PanoBackupJob.Status.DONE

            return
        }

        val (code, rolledBack) = describe(error)

        job.error = code
        job.message = error.message?.take(500)
        job.rolledBack = rolledBack
        job.details = (error as? PanoHostException)?.extras?.takeIf { !it.isEmpty }
        job.status = PanoBackupJob.Status.FAILED

        if (code == INTERNAL) {
            logger.error("Pano backup ${job.type.name.lowercase()} failed", error)
        } else {
            logger.warn("Pano backup ${job.type.name.lowercase()} failed: $code ${error.message ?: ""}")
        }
    }

    private suspend fun createLocked(passphrase: CharArray?, tag: PanoBackupTag, createdBy: String?): PanoBackupInfo {
        val id = store.newId()
        val part = withContext(Dispatchers.IO) { store.partFile(id) }

        try {
            val manifest = archiveTo(part, passphrase)

            val info = PanoBackupInfo(
                id = id,
                createdAt = manifest.createdAt,
                sizeBytes = part.length(),
                encrypted = passphrase != null && passphrase.isNotEmpty(),
                tag = tag,
                panoVersion = host.panoVersion,
                fileCount = manifest.files.count,
                dbSizeBytes = manifest.db?.sizeBytes ?: 0L,
                createdBy = createdBy
            )

            withContext(Dispatchers.IO) { store.commit(info) }

            return info
        } catch (e: Throwable) {
            withContext(Dispatchers.IO) { part.delete() }

            throw e
        }
    }

    /**
     * Writes an archive of this Pano to [file] (passphrase E2E when [passphrase] is non-empty, else
     * plain). Only call with the lock held (inside [startTask] / [tryTask]).
     */
    override suspend fun archiveTo(file: File, passphrase: CharArray?): ArchiveManifest {
        val encryption = passphrase?.takeIf { it.isNotEmpty() }?.let { PanoArcEncryption.Passphrase(it.copyOf()) }

        try {
            return withConnection { connection ->
                val output = withContext(Dispatchers.IO) { file.outputStream().buffered(256 * 1024) }

                InstanceArchiver(host.layout, host.dbPrefix(), host.panoVersion, ArchiveSource(hosted = false))
                    .archive(output, encryption, connection, limits)
            }
        } finally {
            (encryption as? PanoArcEncryption.Passphrase)?.passphrase?.fill('\u0000')
        }
    }

    /**
     * Restores [source] over this Pano (see the class doc for the order). Only call with the lock
     * held (inside [startTask] / [tryTask]); returns the safety backup when one was taken.
     */
    override suspend fun restoreFrom(
        source: File,
        passphrase: CharArray?,
        safetyArchive: Boolean,
        maintenance: Boolean
    ): PanoBackupInfo? {
        val restorer = restorer()
        val keys = PanoArcKeys(passphrase = passphrase?.takeIf { it.isNotEmpty() })

        // Everything that can reject the archive happens here, before anything is changed.
        val staged = withContext(Dispatchers.IO) { source.inputStream().buffered(256 * 1024).use { restorer.stage(it, keys) } }

        try {
            // Opened before anything changes, so a bad target database fails with nothing touched.
            val connection = connect()
            var safety: PanoBackupInfo? = null

            try {
                val previousMaintenance = if (maintenance) host.setMaintenance(true) else null

                safety = try {
                    if (safetyArchive) createLocked(null, PanoBackupTag.PRE_RESTORE, null) else null
                } catch (e: Throwable) {
                    previousMaintenance?.let { host.setMaintenance(it) }

                    throw PanoBackupException(SAFETY_BACKUP_FAILED, e.message, cause = e)
                }

                try {
                    restorer.apply(staged, connection) { host.applyConfig(forTarget(it)) }
                } catch (e: Throwable) {
                    if (safety == null) {
                        throw PanoBackupException(RESTORE_FAILED, e.message, cause = e)
                    }

                    rollback(safety!!, e)
                    previousMaintenance?.let { host.setMaintenance(it) }

                    throw PanoBackupException(RESTORE_FAILED, e.message, rolledBack = true, cause = e)
                }
            } finally {
                close(connection)
            }

            host.restoreApplied()

            return safety
        } finally {
            withContext(Dispatchers.IO) { restorer.discard(staged) }
        }
    }

    /** Puts the pre-restore state back after a failed apply; throws [ROLLBACK_FAILED] if that fails too. */
    private suspend fun rollback(safety: PanoBackupInfo, error: Throwable) {
        logger.error("Restore failed, rolling back to the pre-restore backup ${safety.id}", error)

        try {
            withConnection { connection ->
                withContext(Dispatchers.IO) { store.archiveFile(safety.id).inputStream().buffered(256 * 1024) }.use { input ->
                    restorer().restore(input, PanoArcKeys.NONE, connection) { host.applyConfig(forTarget(it)) }
                }
            }
        } catch (rollbackError: Throwable) {
            logger.error("Rolling back the failed restore failed too; Pano stays in maintenance mode", rollbackError)

            throw PanoBackupException(ROLLBACK_FAILED, error.message, cause = error)
        }
    }

    private fun restorer() = InstanceRestorer(
        layout = host.layout,
        targetConfig = host.targetConfig(),
        targetSchemeVersions = host.knownSchemeVersions(),
        targetConfigVersion = host.configVersion(),
        limits = limits
    )

    /** The database type belongs to the machine too (a portable Windows DB is not portable elsewhere). */
    private fun forTarget(config: JsonObject): JsonObject {
        val type = host.targetConfig().getJsonObject("database")?.getString("type") ?: return config

        config.getJsonObject("database")?.put("type", type)

        return config
    }

    private suspend fun connect(): SqlConnection = try {
        host.connect()
    } catch (e: Throwable) {
        throw PanoBackupException(DATABASE_CONNECTION_FAILED, e.message, cause = e)
    }

    private suspend fun close(connection: SqlConnection) {
        try {
            connection.close().coAwait()
        } catch (_: Exception) {
        }
    }

    private suspend fun <T> withConnection(block: suspend (SqlConnection) -> T): T {
        val connection = connect()

        try {
            return block(connection)
        } finally {
            close(connection)
        }
    }

    companion object {
        const val BUSY = "BACKUP_BUSY"
        const val RESTORE_FAILED = "RESTORE_FAILED"
        const val ROLLBACK_FAILED = "ROLLBACK_FAILED"
        const val SAFETY_BACKUP_FAILED = "SAFETY_BACKUP_FAILED"
        const val DATABASE_CONNECTION_FAILED = "DATABASE_CONNECTION_FAILED"
        const val INTERNAL = "INTERNAL_ERROR"

        const val PHASE_ARCHIVING = "ARCHIVING"
        const val PHASE_UPLOADING = "UPLOADING"
        const val PHASE_DOWNLOADING = "DOWNLOADING"
        const val PHASE_RESTORING = "RESTORING"
        const val PHASE_FETCHING = "FETCHING"

        /** Stable error code for the UI + whether the rollback put the previous state back. */
        fun describe(error: Throwable): Pair<String, Boolean> = when (error) {
            is PanoArcException -> error.code.name to false
            is PanoHostException -> error.code to false
            is PanoBackupException -> {
                val cause = error.cause

                if (error.code == RESTORE_FAILED && cause is PanoArcException) cause.code.name to error.rolledBack
                else error.code to error.rolledBack
            }

            else -> INTERNAL to false
        }
    }
}
