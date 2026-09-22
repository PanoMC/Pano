package com.panomc.platform.server.plugins

import com.panomc.platform.node.ServerTaskKind
import com.panomc.platform.node.ServerTaskStatus

/**
 * What a finished node task means for the provenance row an install created.
 *
 * The row is written before the node has downloaded anything, because that is the only moment
 * Pano knows which project and which version were asked for — the node is told a URL and a
 * filename and nothing else. That makes the row a promise until the task ends, and the whole
 * correctness of the update check rests on which way the promise is resolved: a row left behind by
 * a download that never landed would claim a version is installed that is not there, and would be
 * offered an update to something the server does not have.
 *
 * Pulled out as a pure decision because the alternative is proving it through a database: the
 * interesting cases are a late frame for an already-finished task, a status that is not terminal
 * yet, and a task of a different kind entirely, and all three are one function call to check here.
 */
object PluginInstallTracking {
    /** What to do with the row a `PLUGIN_INSTALL` task owns. */
    enum class Outcome {
        /** Nothing: not a plugin install, or it has not finished. */
        IGNORE,

        /** The jar is on disk. The row stops belonging to the task, and the jar it replaced is gone. */
        CONFIRM,

        /** The download never landed, so the row it promised goes with it. */
        DISCARD
    }

    fun outcomeOf(kind: ServerTaskKind, status: ServerTaskStatus): Outcome {
        if (kind != ServerTaskKind.PLUGIN_INSTALL) {
            return Outcome.IGNORE
        }

        return when (status) {
            ServerTaskStatus.DONE -> Outcome.CONFIRM
            ServerTaskStatus.FAILED -> Outcome.DISCARD
            else -> Outcome.IGNORE
        }
    }

    /**
     * Whether a row whose jar is no longer in the directory should be forgotten.
     *
     * Two things stop it. A row with a task still owns an install in flight, so the jar is
     * *expected* not to be there yet; and a file list Pano could not read is not evidence of
     * anything — a node that answered nothing would otherwise wipe the provenance of every plugin
     * on the server.
     */
    fun isStale(filename: String, taskId: String?, presentFilenames: Set<String>?): Boolean {
        if (taskId != null) {
            return false
        }

        val present = presentFilenames ?: return false

        return filename !in present
    }
}
