package com.panomc.platform.node

import com.panomc.platform.db.model.ServerTask
import com.panomc.platform.server.ServerPowerAction

/**
 * What a failed install leaves on its server row (`server.installError`), and what that refuses.
 *
 * A fresh install or an import that failed leaves a row the node never registered: nothing it can
 * start, and a START sent to it was dropped by the node with a line in its own log, so the panel's
 * button did nothing at all. The reason used to live only on the task, which the panel shows for
 * ten seconds, so after that the server just looked stopped. It is kept on the row now: the panel
 * shows it until the server is reinstalled, and a start is refused with it instead of vanishing.
 *
 * A failed reinstall is different: the node puts the old server back, which starts as before, so it
 * only replaces a reason that is already there (a reinstall of a server whose install had failed)
 * and never marks a working one. Any install, reinstall, import or restore that ends DONE clears it.
 *
 * Pure, so the rules can be asserted without a database or a node.
 */
object ServerInstallFailure {
    /** Longest reason kept on the row; the full build log stays on the node. */
    const val MAX_ERROR_LENGTH = 2000

    /** Stored when a node failed an install without saying why. */
    const val UNKNOWN_ERROR = "The install failed."

    /**
     * The row's `installError` after a task of [kind] failed with [error], when it was [current];
     * [current] itself when the failure leaves the row as it was.
     */
    fun afterFailure(kind: ServerTaskKind, current: String?, error: String?): String? {
        val reason = error?.trim()?.takeIf { it.isNotEmpty() }?.take(MAX_ERROR_LENGTH) ?: UNKNOWN_ERROR

        return when (kind) {
            ServerTaskKind.INSTALL, ServerTaskKind.IMPORT -> reason
            ServerTaskKind.REINSTALL -> if (current != null) reason else null
            else -> current
        }
    }

    /** Whether a task of [kind] that ended DONE leaves the server installed. */
    fun clearsOnDone(kind: ServerTaskKind): Boolean =
        kind == ServerTaskKind.INSTALL ||
            kind == ServerTaskKind.REINSTALL ||
            kind == ServerTaskKind.IMPORT ||
            kind == ServerTaskKind.RESTORE

    /** What a task that a newer install of its server replaced ends with ([staleBefore]). */
    const val SUPERSEDED_ERROR = "SUPERSEDED"

    /** The kinds that lay a server's files down; one of them replaces what an older one was doing. */
    private val INSTALL_KINDS = setOf(ServerTaskKind.INSTALL, ServerTaskKind.REINSTALL, ServerTaskKind.IMPORT)

    /** Whether [other] is an install of [task]'s server that was started after [task]. */
    private fun isNewerInstall(other: ServerTask, task: ServerTask): Boolean =
        other.uuid != task.uuid &&
            other.serverId != null &&
            other.serverId == task.serverId &&
            other.kind in INSTALL_KINDS &&
            (other.createdAt > task.createdAt || (other.createdAt == task.createdAt && other.id > task.id))

    /**
     * The unfinished installs of [done]'s server that were started before it, which [done] being
     * DONE makes dead: the node has the server [done] laid down, whatever they were doing.
     *
     * One of those is what a node that died mid-install leaves behind. Left alone, it stayed the
     * server's active task -- a bar at 90 % over a server that had since been reinstalled -- until
     * the sweep failed it ten minutes later, and that failure then wrote STOPPED over the new one.
     */
    fun staleBefore(done: ServerTask, unfinished: List<ServerTask>): List<ServerTask> =
        if (done.kind in INSTALL_KINDS) {
            unfinished.filter { it.kind in INSTALL_KINDS && isNewerInstall(done, it) }
        } else {
            emptyList()
        }

    /**
     * Whether a newer install of [task]'s server exists among [tasks], so [task] failing says
     * nothing about the server any more: the newer one's outcome is what the row reflects.
     */
    fun isSuperseded(task: ServerTask, tasks: List<ServerTask>): Boolean =
        task.kind in INSTALL_KINDS && tasks.any { isNewerInstall(it, task) }

    /** Whether [action] needs an installed server: STOP and KILL of nothing are harmless. */
    fun blocks(action: ServerPowerAction, installError: String?): Boolean =
        installError != null && (action == ServerPowerAction.START || action == ServerPowerAction.RESTART)
}
