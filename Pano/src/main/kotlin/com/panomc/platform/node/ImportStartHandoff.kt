package com.panomc.platform.node

import com.panomc.platform.db.model.ServerTask

/**
 * Who starts an imported server: the import's DONE, or the Pano plugin install that follows it.
 *
 * An import is two tasks. The node reports `IMPORT_RESULT` and then the import's DONE; Pano
 * answers the first with `INSTALL_PANO_PLUGIN` and, with auto-start on, used to answer the second
 * with START. Both went out together, so the server booted while the node was still writing the
 * plugin jar: instant on a local node, but a 19 MB download to a remote host outlived Paper's
 * plugin scan ("Failed to open plugin jar plugins/pano.jar"), and the server ran unlinked until
 * its next restart. Now the start rides on the install instead (`startAfter` on
 * `INSTALL_PANO_PLUGIN`): the node starts the server once the install has ended, done or failed,
 * and the import's DONE leaves it alone.
 *
 * The rule is "an install went out carrying the start", not "this software has a Pano plugin":
 * a server whose software has one can still get no install (no build could be resolved, the link
 * threw), and a start withheld on the strength of the software alone would then be sent by
 * nobody. Deciding it from what actually happened mirrors every early return of
 * [ManagedServerImportService.linkPlugin] for free. That it has happened by the time DONE asks is
 * the import task's lock: see `ImportResultEvent`.
 *
 * Pure, so both halves can be asserted without a database or a node.
 */
object ImportStartHandoff {
    /**
     * The `startAfter` the Pano plugin install that follows [task] carries, or null for none.
     *
     * Only an import whose DONE is still to come hands its start over, and it hands over exactly
     * what DONE would have used: [decided] when somebody decided for this task up front
     * ([ServerTaskService.setStartAfter]), the row's [autoStart] otherwise. Every other link -- a
     * Pano plugin update's first link, an `IMPORT_RESULT` arriving for an import that already
     * ended -- carries nothing, and the node then does nothing beyond the install.
     */
    fun startAfterFor(task: ServerTask?, decided: Boolean?, autoStart: Boolean): Boolean? {
        if (task == null || task.kind != ServerTaskKind.IMPORT || task.status.isTerminal) {
            return null
        }

        return decided ?: autoStart
    }

    /**
     * Whether a finished task's DONE sends START itself: when a start is wanted ([start]) and no
     * Pano plugin install took it over ([carriedByLink]).
     */
    fun startsOnDone(start: Boolean, carriedByLink: Boolean): Boolean = start && !carriedByLink
}
