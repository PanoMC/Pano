package com.panomc.node.task

import com.panomc.node.files.BackupService
import com.panomc.node.net.DeleteServerMessage
import com.panomc.node.server.PortReservations
import com.panomc.node.server.ServerRegistry
import com.panomc.node.util.NodeLogger
import com.panomc.node.util.PathSafety
import io.vertx.core.json.JsonObject
import java.io.File

/**
 * Removes a server's files once Pano has decided it should go.
 *
 * Pano keeps the row visible until this reports DONE, which is why a failure here is reported
 * rather than swallowed: a delete that half-worked leaves something an admin can see and retry
 * instead of a directory nobody can reach from the panel any more.
 *
 * "The server's files" includes its backups (SM-64, §2.4.29 A). They live outside the server
 * directory on purpose -- a reinstall or a wipe must not take them along -- but a delete is the
 * one operation that is meant to, and Pano drops the backup rows on the assumption that it did.
 * Before this, every deleted server left `<data>/backups/<uuid>` behind: zips and a snapshot
 * repository nothing listed and nothing would ever clean up. A backup directory that cannot be
 * removed fails the task exactly like a server file that cannot be removed.
 *
 * A server directory that is already gone is not an error: Pano also sends this for a server it
 * force-deleted while the node was offline, and by then only the backups may be left.
 *
 * A server adopted in place is the exception to "its files": its directory was somebody's server
 * before Pano and stays theirs after it. Deleting one stops it and removes only what the node put
 * there — `server.json` and `.pano-node/` (the process record, the launcher, the console log) — plus
 * its entry in the external-server index and its backups, which live in the node's data directory
 * and are Pano's. Worlds, plugins, configs and the jar stay exactly where they are. Whether a server
 * is in place is read from the index, never from a flag in the request, so no message can talk the
 * node into deleting an adopted directory.
 */
class DeleteService(
    private val registry: ServerRegistry,
    private val reporter: TaskSink,
    private val logger: NodeLogger,
    /** The daemon's data directory, whose `backups` folder holds every server's backups. */
    dataDir: File,
    /** Port claims of installs in flight; a claim for a server being deleted is dropped with it. */
    private val portReservations: PortReservations? = null
) {
    private val backupsRoot = File(dataDir, BackupService.BACKUPS_DIRECTORY)

    fun delete(message: DeleteServerMessage) {
        val uuid = message.serverUuid
        val taskId = message.taskId

        if (uuid.isNullOrBlank() || taskId.isNullOrBlank()) {
            logger.warn("Ignoring a DELETE_SERVER with no server uuid or task id.")

            return
        }

        if (!PathSafety.isSafeSegment(uuid)) {
            reporter.failed(taskId, uuid, KIND, "The server id is not a usable directory name.")

            return
        }

        try {
            reporter.running(taskId, uuid, KIND, 10, "Stopping the server")

            registry.get(uuid)?.shutdown()

            val directory = registry.directoryFor(uuid)

            val inPlace = registry.isInPlace(uuid)

            if (inPlace) {
                reporter.running(taskId, uuid, KIND, 40, "Removing Pano's files")

                val left = InPlaceAdoption.releaseFiles(directory)

                if (left.isNotEmpty()) {
                    throw IllegalStateException("Some of Pano's files could not be removed: ${left.joinToString(", ")}.")
                }

                registry.unregister(uuid)

                registry.forgetExternal(uuid)

                logger.info("Released server $uuid; its files stay in ${directory.absolutePath}.")
            } else {
                reporter.running(taskId, uuid, KIND, 40, "Deleting files")

                if (directory.exists() && !directory.deleteRecursively()) {
                    throw IllegalStateException("Some files could not be removed from ${directory.absolutePath}.")
                }

                registry.unregister(uuid)
            }

            portReservations?.release(uuid)

            reporter.running(taskId, uuid, KIND, 75, "Deleting backups")

            val backups = PathSafety.resolveUnder(backupsRoot, uuid)

            if (backups.exists() && !backups.deleteRecursively()) {
                throw IllegalStateException("Some backups could not be removed from ${backups.absolutePath}.")
            }

            logger.info("Deleted server $uuid and its backups.")

            reporter.done(
                taskId,
                uuid,
                KIND,
                if (inPlace) "Removed from Pano; the server's files were kept" else "Deleted",
                if (inPlace) JsonObject().put("filesKept", true) else null
            )
        } catch (exception: Exception) {
            logger.error("Deleting $uuid failed: ${exception.message}", exception)

            reporter.failed(taskId, uuid, KIND, exception.message ?: exception.javaClass.simpleName)
        }
    }

    companion object {
        private const val KIND = "DELETE"
    }
}
