package com.panomc.platform.server

import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.PanelConfig
import io.vertx.sqlclient.SqlClient
import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.context.annotation.Lazy
import org.springframework.context.annotation.Scope
import org.springframework.stereotype.Component

/**
 * The server a panel user has selected (`panel_config` option `selected_server`), for the flows
 * that bring a new server into Pano on that user's behalf.
 *
 * Creating a server or linking one through the Pano Agent should leave somebody who had nothing
 * selected looking at the server they just added, not at an empty servers workspace with a
 * "select a server" prompt. Somebody who had already picked one keeps it: adding a second server is
 * not a request to switch away from the first.
 */
@Lazy
@Component
@Scope(value = ConfigurableBeanFactory.SCOPE_SINGLETON)
class ServerSelectionService(
    private val databaseManager: DatabaseManager
) {
    /**
     * Selects [serverId] for [userId] unless the user already has a server selected that still
     * exists. A selection pointing at a deleted server counts as none.
     *
     * @return whether the selection changed.
     */
    suspend fun selectIfNone(userId: Long, serverId: Long, sqlClient: SqlClient): Boolean {
        if (userId < 0) {
            return false
        }

        val current = databaseManager.panelConfigDao.byUserIdAndOption(userId, OPTION, sqlClient)

        if (current == null) {
            databaseManager.panelConfigDao.add(
                PanelConfig(userId = userId, option = OPTION, value = serverId.toString()),
                sqlClient
            )

            return true
        }

        val selected = current.value.toLongOrNull()

        if (selected != null && selected != serverId && databaseManager.serverDao.existsById(selected, sqlClient)) {
            return false
        }

        if (selected != serverId) {
            databaseManager.panelConfigDao.updateValueById(current.id, serverId.toString(), sqlClient)
        }

        return selected != serverId
    }

    companion object {
        /** The `panel_config` option the selection is stored under. */
        const val OPTION = "selected_server"
    }
}
