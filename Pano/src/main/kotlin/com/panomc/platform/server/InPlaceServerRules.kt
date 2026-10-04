package com.panomc.platform.server

import com.panomc.platform.db.model.Server
import com.panomc.platform.error.InPlaceUnsupported

/**
 * What changes for a server its node runs in place, from a directory that was already there
 * (`server.inPlace`, the "Pano Agent" link), rather than one the node made.
 *
 * Two rules, kept here so every endpoint asks the same question the same way:
 *
 * - it can be neither reinstalled nor switched to other software ([requireReinstallable]): both
 *   build a fresh directory next to the old one and swap them, and "next to" somebody's own server
 *   directory is not a place Pano creates folders in. The node refuses the same thing on its side
 *   with the same code, so an older panel that still offers the button fails cleanly.
 * - removing it keeps its files ([filesKeptOnRemoval]): the node takes back only what it put there
 *   (`server.json`, `.pano-node/`) and the backups in its own data directory. A linked server's
 *   files were never Pano's to begin with, so the same answer holds for it.
 */
object InPlaceServerRules {
    /** Throws `IN_PLACE_UNSUPPORTED` for a server that runs in place. */
    fun requireReinstallable(server: Server) {
        if (server.inPlace) {
            throw InPlaceUnsupported()
        }
    }

    /** Whether removing [server] from Pano leaves its files where they are. */
    fun filesKeptOnRemoval(server: Server): Boolean = !server.isManaged || server.inPlace
}
