package com.panomc.node.files

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.panomc.node.agent.AgentFiles
import com.panomc.node.agent.AgentLayout
import com.panomc.node.backup.BackupMode
import com.panomc.node.backup.BackupScope
import com.panomc.node.backup.BackupScopeException
import com.panomc.node.backup.BackupScopeKind
import com.panomc.node.backup.WorldReplacement
import com.panomc.node.backup.snapshot.CorruptChunkException
import com.panomc.node.backup.snapshot.RepoBusyException
import com.panomc.node.backup.snapshot.SnapshotRepository
import com.panomc.node.console.ConsoleLevel
import com.panomc.node.console.ConsoleLogWriter
import com.panomc.node.net.BackupCreateMessage
import com.panomc.node.net.BackupDeleteMessage
import com.panomc.node.net.BackupListMessage
import com.panomc.node.net.BackupRestoreMessage
import com.panomc.node.net.NodeProtocol
import com.panomc.node.net.PlatformConnection
import com.panomc.node.server.DetachedFiles
import com.panomc.node.server.ServerProcess
import com.panomc.node.server.ServerProcessState
import com.panomc.node.server.ServerRegistry
import com.panomc.node.task.TaskReporter
import com.panomc.node.util.NodeLogger
import com.panomc.node.util.PathSafety
import com.panomc.node.util.Sha256
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import java.io.File
import java.io.OutputStream
import java.util.zip.ZipFile

/**
 * What `<backupId>.json` holds next to each backup.
 *
 * [mode], [scope], [fileCount] and [storedBytes] arrived with backups v2; a meta file written
 * before them reads back as the FULL archive of everything it was, with the two counts unknown.
 * [sha256] is the archive's checksum for a FULL backup and null for a SNAPSHOT, which has no
 * single file to checksum — its chunks are each named by their own. [sizeBytes] is the archive
 * size for FULL and the total size of the files in it for SNAPSHOT; [storedBytes] is what this
 * backup actually added to the disk.
 */
data class BackupMeta(
    val id: String = "",
    val name: String = "",
    val sizeBytes: Long = 0,
    val sha256: String? = null,
    val createdAt: Long = 0,
    val mode: String = BackupMode.FULL.name,
    val scope: String = BackupScopeKind.ALL.name,
    val fileCount: Long? = null,
    val storedBytes: Long? = null
)

/**
 * A backup that is streamed rather than sent from a file: a snapshot, zipped as it is read.
 *
 * [write] produces the whole archive into the stream it is given and does not close it.
 * [sizeBytes] is the total of the files in it — not the size of the zip, which nobody knows until
 * it exists — and is only there to refuse a download that could never fit before a byte is sent.
 */
class VirtualArchive(
    val fileName: String,
    val sizeBytes: Long,
    val write: (OutputStream, Long) -> Unit
)

/**
 * Takes and restores copies of a managed server's directory.
 *
 * Two things make this more than "zip a folder". A running Minecraft server holds its world in
 * memory and writes it out when it feels like it, so a copy taken mid-tick is a copy of a world
 * that was never consistent — `save-off` / `save-all` / `save-on` around the read is what turns it
 * into one that was. And a restore overwrites the very files a running server has open, so it is
 * refused unless the server is stopped, and it takes its own copy of the worlds first: a restore
 * of the wrong backup is otherwise the one mistake in this whole system that cannot be undone.
 *
 * Backups live in `<data>/backups/<serverUuid>/`, outside the server directory, so a wipe or a
 * reinstall of the server cannot take its own backups with it.
 *
 * Two kinds live there side by side. A FULL backup is `<id>.zip` — one self-contained archive that
 * can be downloaded and unzipped anywhere. A SNAPSHOT is a manifest in the server's
 * [SnapshotRepository] under `repo/`, deduplicated against every other snapshot, so a daily
 * snapshot of a big world costs what changed that day rather than the whole world again. Both get
 * the same `<id>.json` meta, which is what BACKUP_LIST reads; both take the same scopes (everything,
 * only the worlds, or a chosen list) through [BackupScope]; and both restore with the same rule —
 * a world in the backup replaces the world on disk as a whole.
 */
class BackupService(
    private val dataDir: File,
    private val registry: ServerRegistry,
    private val reporter: TaskReporter,
    private val connection: PlatformConnection,
    private val logger: NodeLogger
) {
    private val backupsRoot = File(dataDir, BACKUPS_DIRECTORY)

    /** Creates a backup and announces it, reporting progress as a task. */
    fun create(message: BackupCreateMessage) {
        val uuid = message.serverUuid
        val taskId = message.taskId
        val backupId = message.backupId

        if (uuid.isNullOrBlank() || taskId.isNullOrBlank() || backupId.isNullOrBlank()) {
            logger.warn("Ignoring a BACKUP_CREATE with no server uuid, task id or backup id.")

            return
        }

        if (!PathSafety.isSafeSegment(uuid) || !PathSafety.isSafeSegment(backupId)) {
            reporter.failed(taskId, uuid, KIND_BACKUP, "The server or backup id is not a usable name.")

            return
        }

        val server = registry.get(uuid)

        if (server == null) {
            reporter.failed(taskId, uuid, KIND_BACKUP, "This node does not have that server.")

            return
        }

        val mode = BackupMode.parse(message.mode)
        val scopeKind = BackupScopeKind.parse(message.scope)

        if (mode == null || scopeKind == null) {
            reporter.failed(taskId, uuid, KIND_BACKUP, BackupScope.ERROR_INVALID_SCOPE)

            return
        }

        val exclude = BackupExcludeMatcher(message.exclude, server.directory)

        val scope = try {
            BackupScope.resolve(server.directory, scopeKind, message.include, exclude)
        } catch (exception: BackupScopeException) {
            reporter.failed(taskId, uuid, KIND_BACKUP, exception.code)

            return
        }

        val name = message.name?.takeIf { it.isNotBlank() } ?: backupId
        val include = message.include.orEmpty().map { it.trim() }.filter { it.isNotEmpty() }

        var flushed = false

        val meta: BackupMeta
        val summary: String

        try {
            reporter.running(taskId, uuid, KIND_BACKUP, PREPARE_PERCENT, "Preparing")

            flushed = pauseWorldSaves(server)

            if (mode == BackupMode.SNAPSHOT) {
                val result = repository(uuid).create(
                    server.directory,
                    backupId,
                    name,
                    System.currentTimeMillis(),
                    scope,
                    if (scopeKind == BackupScopeKind.CUSTOM) include else emptyList(),
                    exclude
                ) { percent, text -> reporter.running(taskId, uuid, KIND_BACKUP, percent, text) }

                meta = BackupMeta(
                    id = backupId,
                    name = name,
                    sizeBytes = result.manifest.logicalBytes,
                    sha256 = null,
                    createdAt = result.manifest.createdAt,
                    mode = BackupMode.SNAPSHOT.name,
                    scope = scopeKind.name,
                    fileCount = result.manifest.fileCount,
                    storedBytes = result.storedBytes
                )

                summary = "Snapshot: ${result.newChunks} new of ${result.totalChunks} (${meta.fileCount} files)"
            } else {
                meta = createArchive(server, uuid, taskId, backupId, name, scopeKind, scope.roots, scope.wholeDirectory, exclude)

                summary = "Backed up ${meta.sizeBytes} bytes"
            }
        } catch (exception: Exception) {
            archiveFile(uuid, backupId).delete()

            val error = when (exception) {
                is RepoBusyException -> SnapshotRepository.ERROR_REPO_BUSY
                is BackupScopeException -> exception.code
                else -> exception.message ?: exception.javaClass.simpleName
            }

            logger.error("Backup of $uuid failed: ${exception.message}", exception)

            reporter.failed(taskId, uuid, KIND_BACKUP, error)

            return
        } finally {
            if (flushed) {
                resumeWorldSaves(server)
            }
        }

        writeMeta(uuid, meta)

        server.emit(ConsoleLevel.INFO, "Pano finished a backup (${meta.name}).")

        connection.send(
            NodeProtocol.Outbound.BACKUP_CREATED,
            JsonObject()
                .put("serverUuid", uuid)
                .put("backup", toJson(meta))
        )

        reporter.done(taskId, uuid, KIND_BACKUP, summary)
    }

    /**
     * A FULL backup: the scope zipped into `<id>.zip`, then checksummed.
     *
     * Everything is zipped from the server directory itself, as it always was, so an ALL backup is
     * byte-for-byte the archive a node before scopes would have written; WORLDS and CUSTOM zip
     * their roots instead.
     */
    private fun createArchive(
        server: ServerProcess,
        uuid: String,
        taskId: String,
        backupId: String,
        name: String,
        scopeKind: BackupScopeKind,
        roots: List<String>,
        wholeDirectory: Boolean,
        exclude: BackupExcludeMatcher
    ): BackupMeta {
        val archive = archiveFile(uuid, backupId)

        reporter.running(taskId, uuid, KIND_BACKUP, ARCHIVE_PERCENT, "Archiving the server directory")

        archive.parentFile?.mkdirs()

        ZipTool.archive(server.directory, if (wholeDirectory) listOf("") else roots, archive) { path ->
            exclude.matches(path)
        }

        reporter.running(taskId, uuid, KIND_BACKUP, HASH_PERCENT, "Checksumming")

        val fileCount = ZipFile(archive).use { zip -> zip.stream().filter { !it.isDirectory }.count() }

        return BackupMeta(
            id = backupId,
            name = name,
            sizeBytes = archive.length(),
            sha256 = Sha256.of(archive),
            createdAt = System.currentTimeMillis(),
            mode = BackupMode.FULL.name,
            scope = scopeKind.name,
            fileCount = fileCount,
            storedBytes = archive.length()
        )
    }

    /** Every backup this node holds for one server, newest first. */
    fun list(message: BackupListMessage): JsonObject {
        val uuid = message.serverUuid

        if (uuid.isNullOrBlank() || !PathSafety.isSafeSegment(uuid)) {
            return FileService.failure(FileService.ERROR_UNKNOWN_SERVER)
        }

        val backups = JsonArray()

        readAll(uuid).forEach { meta -> backups.add(toJson(meta)) }

        return FileService.success().put("backups", backups)
    }

    /**
     * Puts a backup back, over a server that must already be stopped.
     *
     * The worlds are copied aside first and left behind on purpose: the copy is small compared to
     * the backup it is protecting against, and an operator who restored the wrong thing has
     * minutes rather than a support ticket.
     *
     * Every world directory the backup holds is emptied before its copy is written back (see
     * [WorldReplacement]); everything else is laid over what is there, as it always was. A
     * snapshot is verified chunk by chunk before anything is touched, so a damaged repository fails
     * with `CORRUPT_CHUNK <sha>` and leaves the server as it was.
     */
    fun restore(message: BackupRestoreMessage) {
        val uuid = message.serverUuid
        val taskId = message.taskId
        val backupId = message.backupId

        if (uuid.isNullOrBlank() || taskId.isNullOrBlank() || backupId.isNullOrBlank()) {
            logger.warn("Ignoring a BACKUP_RESTORE with no server uuid, task id or backup id.")

            return
        }

        if (!PathSafety.isSafeSegment(uuid) || !PathSafety.isSafeSegment(backupId)) {
            reporter.failed(taskId, uuid, KIND_RESTORE, "The server or backup id is not a usable name.")

            return
        }

        val server = registry.get(uuid)

        if (server == null) {
            reporter.failed(taskId, uuid, KIND_RESTORE, "This node does not have that server.")

            return
        }

        if (server.state != ServerProcessState.STOPPED && server.state != ServerProcessState.CRASHED) {
            reporter.failed(taskId, uuid, KIND_RESTORE, "Stop the server before restoring a backup.")

            return
        }

        val archive = archiveFile(uuid, backupId)
        val repository = repository(uuid)

        val isArchive = archive.isFile && ZipTool.isZip(archive)

        if (!isArchive && !repository.has(backupId)) {
            reporter.failed(taskId, uuid, KIND_RESTORE, "That backup is not on this node any more.")

            return
        }

        try {
            var safety: File? = null

            if (isArchive) {
                reporter.running(taskId, uuid, KIND_RESTORE, PREPARE_PERCENT, "Copying the current worlds aside")

                safety = safetyCopy(uuid, server)

                reporter.running(taskId, uuid, KIND_RESTORE, ARCHIVE_PERCENT, "Restoring files")

                restoreArchive(server.directory, archive)
            } else {
                repository.restore(
                    backupId,
                    server.directory,
                    beforeWrite = {
                        reporter.running(taskId, uuid, KIND_RESTORE, SAFETY_PERCENT, "Copying the current worlds aside")

                        safety = safetyCopy(uuid, server)
                    }
                ) { percent, text -> reporter.running(taskId, uuid, KIND_RESTORE, percent, text) }
            }

            server.emit(ConsoleLevel.INFO, "Pano restored a backup; the previous worlds are in ${safety?.name}.")

            logger.info("Restored $backupId into $uuid (safety copy ${safety?.absolutePath}).")

            reporter.done(taskId, uuid, KIND_RESTORE, "Restored")
        } catch (exception: Exception) {
            val error = when (exception) {
                is RepoBusyException, is CorruptChunkException -> exception.message!!
                else -> exception.message ?: exception.javaClass.simpleName
            }

            logger.error("Restoring $backupId into $uuid failed: ${exception.message}", exception)

            reporter.failed(taskId, uuid, KIND_RESTORE, error)
        }
    }

    /** Removes one backup and its meta; for a snapshot, also every chunk nothing else needs. */
    fun delete(message: BackupDeleteMessage): JsonObject {
        val uuid = message.serverUuid
        val backupId = message.backupId

        if (uuid.isNullOrBlank() || backupId.isNullOrBlank()) {
            return FileService.failure(FileService.ERROR_NOT_FOUND)
        }

        if (!PathSafety.isSafeSegment(uuid) || !PathSafety.isSafeSegment(backupId)) {
            return FileService.failure(FileService.ERROR_PATH_DENIED)
        }

        val repository = repository(uuid)

        if (repository.has(backupId)) {
            try {
                val gc = repository.delete(backupId) { metaFile(uuid, backupId).delete() }

                logger.info(
                    "Deleted snapshot $backupId of $uuid" +
                        if (gc.skipped) {
                            "; a manifest could not be read, so no chunks were collected."
                        } else {
                            " and ${gc.deletedChunks} unused chunk(s), ${gc.freedBytes} bytes."
                        }
                )
            } catch (_: RepoBusyException) {
                return FileService.failure(SnapshotRepository.ERROR_REPO_BUSY)
            }

            return FileService.success()
        }

        val archive = archiveFile(uuid, backupId)

        archive.delete()
        metaFile(uuid, backupId).delete()

        logger.info("Deleted backup $backupId of $uuid.")

        return FileService.success()
    }

    /**
     * The archive behind a `@backup/<id>` transfer path.
     *
     * Backups live outside the server directory, so they cannot be reached by the ordinary path
     * rules; this is the one door to them, and it only opens for exactly that prefix.
     */
    fun resolveVirtual(serverUuid: String, path: String): File? {
        val backupId = virtualBackupId(serverUuid, path) ?: return null

        return archiveFile(serverUuid, backupId).takeIf { it.isFile }
    }

    /**
     * The snapshot behind a `@backup/<id>` transfer path, as a zip written on the fly.
     *
     * Null when the id is not a snapshot — a FULL backup is a real file and goes through
     * [resolveVirtual]. The zip is named `<id>.zip`, the same name a FULL download gets.
     */
    fun resolveVirtualArchive(serverUuid: String, path: String): VirtualArchive? {
        val backupId = virtualBackupId(serverUuid, path) ?: return null
        val repository = repository(serverUuid)

        if (archiveFile(serverUuid, backupId).isFile || !repository.has(backupId)) {
            return null
        }

        val manifest = repository.manifest(backupId) ?: return null

        return VirtualArchive("$backupId$ARCHIVE_SUFFIX", manifest.logicalBytes) { output, maxBytes ->
            repository.writeZip(backupId, output, maxBytes)
        }
    }

    private fun virtualBackupId(serverUuid: String, path: String): String? {
        if (!path.startsWith(VIRTUAL_PREFIX)) {
            return null
        }

        val backupId = path.removePrefix(VIRTUAL_PREFIX)

        if (!PathSafety.isSafeSegment(serverUuid) || !PathSafety.isSafeSegment(backupId)) {
            return null
        }

        return backupId
    }

    /**
     * Tells a running server to stop writing its worlds, and waits for the flush to land.
     *
     * Best effort by design: the commands only exist on a Bukkit-family or vanilla console, and a
     * proxy has no world at all. A backup of a server that ignored them is still a backup — it is
     * simply a backup of whatever was on disk, which is what every naive tool gives you anyway.
     */
    private fun pauseWorldSaves(server: ServerProcess): Boolean {
        if (server.state != ServerProcessState.RUNNING || server.spec.isProxy) {
            return false
        }

        if (!server.sendCommand(COMMAND_SAVE_OFF, ISSUER)) {
            return false
        }

        server.sendCommand(COMMAND_SAVE_ALL, ISSUER)

        // No completion to wait on: the console gives no acknowledgement, so the only thing to do
        // is give the server a moment to write before reading the same files.
        Thread.sleep(SAVE_FLUSH_MILLIS)

        return true
    }

    private fun resumeWorldSaves(server: ServerProcess) {
        server.sendCommand(COMMAND_SAVE_ON, ISSUER)
    }

    /** Zips everything matching `world*` into a timestamped archive beside the backups. */
    private fun safetyCopy(uuid: String, server: ServerProcess): File {
        val worlds = server.directory.listFiles()
            ?.filter { it.isDirectory && it.name.startsWith(WORLD_PREFIX) }
            ?.map { it.name }
            .orEmpty()

        val target = File(serverBackupsDir(uuid), "pre-restore-${System.currentTimeMillis()}.zip")

        target.parentFile?.mkdirs()

        if (worlds.isEmpty()) {
            // Still written, so a restore always leaves a marker of what it replaced.
            ZipTool.archive(server.directory, listOf(""), target) { true }

            return target
        }

        ZipTool.archive(server.directory, worlds, target)

        return target
    }

    private fun readAll(uuid: String): List<BackupMeta> {
        val directory = serverBackupsDir(uuid)

        val metas = directory.listFiles()
            ?.filter { it.isFile && it.name.endsWith(META_SUFFIX) }
            ?.mapNotNull { file ->
                try {
                    gson.fromJson(file.readText(), BackupMeta::class.java)
                } catch (exception: Exception) {
                    logger.warn("Could not read ${file.absolutePath}: ${exception.message}")

                    null
                }
            }
            .orEmpty()

        return metas.sortedByDescending { it.createdAt }
    }

    private fun writeMeta(uuid: String, meta: BackupMeta) {
        val file = metaFile(uuid, meta.id)

        file.parentFile?.mkdirs()
        file.writeText(gson.toJson(meta))
    }

    private fun serverBackupsDir(uuid: String): File = PathSafety.resolveUnder(backupsRoot, uuid)

    /** `<data>/backups/<uuid>/repo`, the server's snapshot repository. */
    private fun repository(uuid: String): SnapshotRepository =
        SnapshotRepository(File(serverBackupsDir(uuid), REPOSITORY_DIRECTORY))

    private fun archiveFile(uuid: String, backupId: String): File =
        PathSafety.resolveUnder(serverBackupsDir(uuid), "$backupId$ARCHIVE_SUFFIX")

    private fun metaFile(uuid: String, backupId: String): File =
        PathSafety.resolveUnder(serverBackupsDir(uuid), "$backupId$META_SUFFIX")

    private fun toJson(meta: BackupMeta): JsonObject = JsonObject()
        .put("id", meta.id)
        .put("name", meta.name)
        .put("sizeBytes", meta.sizeBytes)
        .put("sha256", meta.sha256)
        .put("createdAt", meta.createdAt)
        .put("mode", meta.mode)
        .put("scope", meta.scope)
        .put("fileCount", meta.fileCount)
        .put("storedBytes", meta.storedBytes)

    companion object {
        const val BACKUPS_DIRECTORY = "backups"
        const val REPOSITORY_DIRECTORY = "repo"
        const val ARCHIVE_SUFFIX = ".zip"
        const val META_SUFFIX = ".json"

        /** Transfer paths that mean "a backup", not "a file inside the server". */
        const val VIRTUAL_PREFIX = "@backup/"

        const val KIND_BACKUP = "BACKUP"
        const val KIND_RESTORE = "RESTORE"

        const val COMMAND_SAVE_OFF = "save-off"
        const val COMMAND_SAVE_ALL = "save-all"
        const val COMMAND_SAVE_ON = "save-on"

        private const val ISSUER = "backup"
        private const val WORLD_PREFIX = "world"

        private const val PREPARE_PERCENT = 5
        private const val SAFETY_PERCENT = 10
        private const val ARCHIVE_PERCENT = 15
        private const val HASH_PERCENT = 85

        /** How long the server is given to finish writing after `save-all`. */
        private const val SAVE_FLUSH_MILLIS = 2_000L

        private val gson: Gson = GsonBuilder().setPrettyPrinting().create()

        /**
         * Extracts a FULL archive over [serverDirectory], replacing every world in it wholesale.
         *
         * The worlds are read from the archive's own entries before anything is written, so a
         * world the backup does not contain is left exactly as it is.
         */
        fun restoreArchive(serverDirectory: File, archive: File) {
            // ZipTool skips the agent's jar and data folder itself, as it skips every denied path.
            val worlds = ZipFile(archive).use { zip ->
                BackupScope.worldsIn(zip.stream().filter { !it.isDirectory }.map { it.name }.toList().asSequence())
            }

            worlds.forEach { world -> WorldReplacement.clear(serverDirectory, world) }

            ZipTool.extract(serverDirectory, archive, serverDirectory)
        }
    }
}

/**
 * Decides what stays out of a backup.
 *
 * Logs, caches and half-downloaded jars are the three things that are always big, always
 * regenerated and never worth restoring. A pattern ending in `/` excludes a whole directory, one
 * containing `*` is a glob on the file name, and anything else is an exact relative path — the
 * same three shapes a person would expect from a .gitignore, kept deliberately small so what a
 * backup contains stays predictable.
 */
class BackupExcludeMatcher(
    patterns: List<String>?,
    /** The server the backup is of, so the Pano Agent's jar is known by more than its name. */
    private val serverDirectory: File? = null
) {
    /** The patterns in effect: the ones given, or [DEFAULT_EXCLUDES] when none were. */
    val patterns: List<String> = (patterns?.takeIf { it.isNotEmpty() } ?: DEFAULT_EXCLUDES)
        .map { it.trim() }
        .filter { it.isNotEmpty() }

    fun matches(path: String): Boolean {
        val normalised = ServerFileDenylist.normalise(path)

        if (normalised.isEmpty()) {
            return false
        }

        val name = normalised.substringAfterLast('/')

        // Whatever the operator asked for: the node's coloured copy of the console is a cache of
        // output, rewritten on every start, and restoring it would splice last week's console into
        // today's history (§2.4.21 B).
        if (isConsoleLog(normalised)) {
            return true
        }

        // Nor the Pano Agent (SM-74): its data folder is the node's, and its jar is not the
        // server's -- restoring last month's backup must not bring last month's agent back with it.
        if (isAgentFile(normalised)) {
            return true
        }

        return patterns.any { pattern ->
            when {
                pattern.endsWith("/") -> {
                    val directory = pattern.trimEnd('/')

                    normalised == directory || normalised.startsWith("$directory/")
                }

                pattern.contains('*') -> globToRegex(pattern).matches(name)

                else -> normalised == pattern
            }
        }
    }

    /** The agent's data folder or its jar (see [AgentFiles]); always out of a backup. */
    private fun isAgentFile(normalised: String): Boolean =
        isAgentData(normalised) ||
            (serverDirectory?.let { AgentFiles.isOwnJar(it, normalised) } ?: (!normalised.contains('/') && AgentLayout.isAgentJarName(normalised)))

    companion object {
        /** What a backup leaves out when Pano does not say otherwise. */
        val DEFAULT_EXCLUDES = listOf("logs/", "cache/", "*.jar.tmp")

        /**
         * The node's runtime files in `.pano-node/` — always excluded, patterns or not: the coloured
         * console history and its rotations, and a launcher-run server's plumbing (SM-62): its
         * launcher, its stdin FIFO — which a backup must never try to read — its `console.out` and
         * the exit code it left.
         */
        fun isConsoleLog(normalised: String): Boolean {
            val prefix = "${ConsoleLogWriter.DIRECTORY}/"

            if (!normalised.startsWith(prefix)) {
                return false
            }

            val rest = normalised.removePrefix(prefix)

            if (rest.contains('/')) {
                return false
            }

            return rest.startsWith(ConsoleLogWriter.FILE_NAME) ||
                DetachedFiles.RUNTIME_FILE_NAMES.any { rest == it || rest.startsWith("$it.") }
        }

        /** `.pano-agent/` and everything in it. */
        fun isAgentData(normalised: String): Boolean =
            normalised == AgentLayout.DATA_DIRECTORY || normalised.startsWith("${AgentLayout.DATA_DIRECTORY}/")

        /** A shell-style glob as a regex, with everything else in the pattern taken literally. */
        fun globToRegex(pattern: String): Regex {
            val escaped = pattern
                .split('*')
                .joinToString(".*") { Regex.escape(it) }

            return Regex("^$escaped$")
        }
    }
}
