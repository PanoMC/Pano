package com.panomc.platform.server.metrics

import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.Server
import com.panomc.platform.panel.PanelRealtimeHub
import io.vertx.sqlclient.SqlClient
import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.context.annotation.Lazy
import org.springframework.context.annotation.Scope
import org.springframework.stereotype.Component

/**
 * Keeps the measured size of a server's directory on its row (§2.4.18 B).
 *
 * Both reporters end up here — the node measures the directory it manages, an agent-lite plugin
 * measures its own — because the figure has to outlive them: the live sample is in memory and a
 * server that is switched off will never send another one, but the files are still there, and a
 * panel that forgot forty gigabytes because Pano restarted would be wrong rather than unsure.
 *
 * The one rule that makes this affordable: **write only when one of the numbers changed**. A measurement
 * arrives with every ten second tick of every server; the figure behind it is re-walked every five
 * minutes at best. Comparing against what the caller's [Server] holds — the row the node path just
 * read, or the object the plugin's socket has held since it connected — turns thousands of writes
 * a day into the handful that carry news, and keeps `notifyServerUpdated` for the moments where
 * something actually moved.
 */
@Lazy
@Component
@Scope(value = ConfigurableBeanFactory.SCOPE_SINGLETON)
class ServerDiskUsageStore(
    private val databaseManager: DatabaseManager,
    private val panelRealtimeHub: PanelRealtimeHub
) {
    /**
     * Stores [used] and [total] against [server] when either is news, and tells the panel when it
     * was.
     *
     * The pair goes in one write because the panel reads it as one: they arrive in the same frame
     * and a used figure stored next to a stale total is a gauge pointing at the wrong mark.
     */
    suspend fun persist(server: Server, used: Long?, total: Long?, sqlClient: SqlClient) {
        val nextUsed = keep(server.diskUsed, used)
        val nextTotal = keep(server.diskTotal, total)

        if (nextUsed == server.diskUsed && nextTotal == server.diskTotal) {
            return
        }

        databaseManager.serverDao.updateDiskUsageById(server.id, nextUsed, nextTotal, sqlClient)

        // Kept in step with the row on purpose: this object is what the next tick compares
        // against, so a stale copy here would mean writing the same numbers again every ten
        // seconds for as long as the connection lasts.
        server.diskUsed = nextUsed
        server.diskTotal = nextTotal

        panelRealtimeHub.notifyServerUpdated(server.id)
    }

    companion object {
        /**
         * What the row should hold: the measurement when there is one, otherwise what is already
         * there.
         *
         * A null measurement is "nobody has read it yet", which is never a reason to erase a
         * figure measured before — including across a restart, where the row is the only place
         * the last known size still exists.
         */
        fun keep(stored: Long?, measured: Long?): Long? = measured ?: stored

        /** Whether storing this measurement over what the row holds would change anything. */
        fun isNews(stored: Long?, measured: Long?): Boolean = keep(stored, measured) != stored
    }
}
