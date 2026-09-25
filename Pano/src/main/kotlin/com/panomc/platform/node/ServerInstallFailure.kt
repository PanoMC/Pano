package com.panomc.platform.node

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

    /** Whether [action] needs an installed server: STOP and KILL of nothing are harmless. */
    fun blocks(action: ServerPowerAction, installError: String?): Boolean =
        installError != null && (action == ServerPowerAction.START || action == ServerPowerAction.RESTART)
}
