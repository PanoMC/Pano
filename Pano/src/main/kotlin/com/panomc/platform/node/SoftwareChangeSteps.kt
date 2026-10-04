package com.panomc.platform.node

import com.panomc.platform.server.software.ServerSoftwareFamily
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * The order a software change runs in (SM-66, §2.4.31), apart from anything that talks to a node.
 *
 * One REINSTALL task covers every step: stop the server if it is running (and wait until it really
 * is stopped), take a full backup if asked, then hand the node the reinstall. The first two are
 * Pano's to run, and a failure in either ends the task before anything on disk was touched — a
 * change that could not back the server up does not go ahead without the backup. What the node
 * does after that (download, Java, the Pano plugin, the swap) it reports on the same task itself;
 * starting the server afterwards hangs off the task's DONE ([ServerTaskService.setStartAfter]).
 *
 * The steps are behind [Actions] so the order and the failure paths can be tested without a node,
 * a database or a clock.
 */
class SoftwareChangeSteps(private val actions: Actions) {
    /** What running the steps needs from the outside world. */
    interface Actions {
        /** Writes a step's message onto the task the panel is following. */
        suspend fun progress(message: String)

        /** Sends a graceful stop and waits for the server to be down; false when it never was. */
        suspend fun stopAndWait(): Boolean

        /** Takes a full backup called [name] and waits for it; null when it worked, else why not. */
        suspend fun backup(name: String): String?

        /** Hands the reinstall to the node, which reports the rest on the same task. */
        suspend fun reinstall()

        /** Ends the task as FAILED with [error]; nothing further runs. */
        suspend fun fail(error: String)
    }

    /** What was decided when the change was asked for. */
    data class Plan(
        val running: Boolean,
        val backupFirst: Boolean,
        val backupName: String
    )

    /** How far a run got. */
    enum class Outcome {
        /** The node has the reinstall; the task continues on its frames. */
        HANDED_TO_NODE,
        STOP_FAILED,
        BACKUP_FAILED,
        REINSTALL_FAILED
    }

    suspend fun run(plan: Plan): Outcome {
        if (plan.running) {
            actions.progress(MESSAGE_STOPPING)

            if (!actions.stopAndWait()) {
                actions.fail(ERROR_STOP)

                return Outcome.STOP_FAILED
            }
        }

        if (plan.backupFirst) {
            actions.progress("Backing up the server (${plan.backupName})")

            val error = actions.backup(plan.backupName)

            if (error != null) {
                actions.fail("$ERROR_BACKUP_PREFIX$error")

                return Outcome.BACKUP_FAILED
            }
        }

        actions.progress(MESSAGE_REINSTALLING)

        return try {
            actions.reinstall()

            Outcome.HANDED_TO_NODE
        } catch (exception: Exception) {
            actions.fail(exception.message ?: exception.javaClass.simpleName)

            Outcome.REINSTALL_FAILED
        }
    }

    companion object {
        const val MESSAGE_STOPPING = "Stopping the server"
        const val MESSAGE_REINSTALLING = "Installing the new server files"

        /** Written into the task when the server did not stop; nothing was changed. */
        const val ERROR_STOP = "The server did not stop, so nothing was changed."

        /** Prefix of the task error when the backup failed; nothing was changed. */
        const val ERROR_BACKUP_PREFIX = "The backup failed, so nothing was changed: "

        private val DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmm").withZone(ZoneOffset.UTC)

        /**
         * `before-<old>-to-<new>-<date>`, e.g. `before-paper-1.21.8-to-purpur-1.21.8-2026-09-24-1830`.
         * Old and new are `software-version`; a reinstall on the same software reads
         * `before-paper-1.21.8-to-paper-1.21.8-…`, which is still what happened.
         */
        fun backupName(
            oldSoftware: String?,
            oldVersion: String?,
            newSoftware: String,
            newVersion: String,
            at: Long
        ): String {
            val old = listOfNotNull(oldSoftware, oldVersion).joinToString("-").ifBlank { "unknown" }

            return "before-$old-to-$newSoftware-$newVersion-${DATE.format(Instant.ofEpochMilli(at))}"
        }

        /**
         * Whether the server is started once the change is DONE: what the request says, else
         * whether it was running when the change was asked for — a change should not leave a
         * server down that was up, nor bring up one somebody had stopped.
         */
        fun startAfter(requested: Boolean?, running: Boolean): Boolean = requested ?: running

        /** Whether a full backup is taken first: what the request says, else yes. */
        fun backupFirst(requested: Boolean?): Boolean = requested ?: true

        /**
         * Whether a change from [from] to [to] would turn a network member's role around: a proxy
         * becoming a backend or the other way, which the network it belongs to cannot survive.
         *
         * [inNetwork] is always false today. Pano stopped grouping servers into proxy networks on
         * 2026-09-23 (`DatabaseMigration45to46` dropped `networkId`/`networkRole`), so no row can
         * be a member; the rule lives here so a future network store has one place to plug into.
         */
        fun networkRoleConflict(
            inNetwork: Boolean,
            from: ServerSoftwareFamily,
            to: ServerSoftwareFamily
        ): Boolean = inNetwork && from.isProxy != to.isProxy

        /**
         * The version a change lands on when the request names none.
         *
         * The row's own version only when the software stays: taking Paper's `1.21.8` over to
         * BungeeCord, whose versions are Jenkins build numbers, would fail the install. Otherwise
         * the target's recommended release.
         */
        suspend fun targetVersion(
            requestedVersion: String?,
            targetSoftware: String,
            currentSoftware: String?,
            currentVersion: String?,
            recommended: suspend (String) -> String?
        ): String? = requestedVersion?.takeIf { it.isNotBlank() }
            ?: currentVersion?.takeIf { targetSoftware == currentSoftware }
            ?: recommended(targetSoftware)
    }
}
