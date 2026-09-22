package com.panomc.node.files

import com.panomc.node.host.ServerDiskUsage
import com.panomc.node.server.ExternalServerIndex
import com.panomc.node.util.NodeLogger
import com.panomc.node.util.PathSafety
import java.io.File

/**
 * Removes the backups of servers that no longer exist, once, at boot (SM-64, §2.4.29 A).
 *
 * A server deleted before SM-64 took only its directory with it, and one force-deleted while its
 * node was offline took nothing at all, so a long-lived node collects `<data>/backups/<uuid>`
 * folders that nothing lists and nothing will ever clean up — a zip per backup and a whole
 * snapshot repository each.
 *
 * The rule is deliberately narrow: a backup folder is an orphan only when **both** its server's
 * directory is missing and no registered server has that uuid. A server directory is never touched
 * here — even one with an unreadable `server.json` that the registry skipped keeps its backups,
 * because a directory is exactly what a server that is merely broken still has. Servers are only
 * ever removed by an explicit delete.
 *
 * A server adopted in place has no directory under `<data>/servers` at all, so "its directory is
 * missing" says nothing about it: every uuid in [ExternalServerIndex] counts as registered, loaded
 * or not (its disk may simply not be mounted yet). An index that exists but cannot be read means
 * the sweep cannot tell an adopted server from a deleted one, and then it removes nothing.
 */
object BackupOrphanSweep {
    /** One backup folder that was removed, and how much it held. */
    data class Removed(val uuid: String, val bytes: Long)

    /**
     * Deletes every orphaned backup folder under [dataDir] and returns what went.
     *
     * [registered] is the registry's uuid set after `load()`. A folder whose name is not a usable
     * uuid segment is left alone: it was not written by this daemon and it is not this code's to
     * judge.
     */
    fun sweep(dataDir: File, registered: Set<String>, logger: NodeLogger): List<Removed> {
        val backupsRoot = File(dataDir, BackupService.BACKUPS_DIRECTORY)
        val serversRoot = File(dataDir, SERVERS_DIRECTORY)

        val candidates = backupsRoot.listFiles()?.filter { it.isDirectory } ?: return emptyList()

        val external = ExternalServerIndex.read(dataDir)

        if (external == null) {
            logger.warn("${ExternalServerIndex.FILE} cannot be read; not looking for orphaned backups this time.")

            return emptyList()
        }

        val removed = mutableListOf<Removed>()

        candidates.forEach { directory ->
            val uuid = directory.name

            if (!PathSafety.isSafeSegment(uuid) ||
                uuid in registered ||
                uuid in external ||
                File(serversRoot, uuid).exists()
            ) {
                return@forEach
            }

            val bytes = try {
                ServerDiskUsage.directorySize(directory)
            } catch (_: Exception) {
                0L
            }

            if (!directory.deleteRecursively()) {
                logger.warn("Could not remove the backups of deleted server $uuid from ${directory.absolutePath}.")

                return@forEach
            }

            logger.info("Removed backups of deleted server $uuid (${megabytes(bytes)} MB).")

            removed.add(Removed(uuid, bytes))
        }

        return removed
    }

    private fun megabytes(bytes: Long): String = String.format(java.util.Locale.ROOT, "%.1f", bytes / (1024.0 * 1024.0))

    private const val SERVERS_DIRECTORY = "servers"
}
