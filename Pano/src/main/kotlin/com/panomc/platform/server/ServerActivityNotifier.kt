package com.panomc.platform.server

import com.panomc.platform.db.model.PanelActivityLog

/**
 * Tells whoever listens that a server's activity feed just grew.
 *
 * Called from the one place every activity entry is written — the DAO's `add` — rather than from
 * each endpoint, so an entry type added later (or written by the node's events: crashes, scheduled
 * runs) reaches the live feed without anyone remembering to wire it. Only entries the per-server
 * activity endpoint would return count: one of [ServerActivityLogTypes.ALL] carrying a `serverId`.
 *
 * A static seam on purpose: the DAO is built before, and underneath, everything that could deliver
 * the news (the realtime hub needs the database manager), so the hub registers itself as [sink]
 * instead of being injected into the DAO.
 */
object ServerActivityNotifier {
    private val types: Set<String> by lazy { ServerActivityLogTypes.ALL.toSet() }

    /** Receives the server id of every server-scoped entry written; null until the hub is up. */
    @Volatile
    var sink: ((Long) -> Unit)? = null

    /** The server [log] belongs to, when it is an entry of that server's activity feed. */
    fun serverIdOf(log: PanelActivityLog): Long? {
        if (log.type !in types) {
            return null
        }

        return (log.details.getValue("serverId") as? Number)?.toLong()
    }

    /** Called after [log] was written. Never throws: a live hint must not fail the write it follows. */
    fun onLogAdded(log: PanelActivityLog) {
        try {
            val serverId = serverIdOf(log) ?: return

            sink?.invoke(serverId)
        } catch (_: Exception) {
        }
    }
}
