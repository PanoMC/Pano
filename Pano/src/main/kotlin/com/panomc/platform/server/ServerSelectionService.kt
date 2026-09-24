package com.panomc.platform.server

import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.PanelConfig
import com.panomc.platform.db.model.SystemProperty
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

    /**
     * Makes [serverId] the main server (the one the website shows) when there is none, or the one
     * on record no longer exists. Accepting a connection request always did this; creating a
     * server or linking one through the Pano Agent did not, which left a first server that was
     * nobody's main and an empty spot wherever the main server is shown.
     *
     * @return whether the main server changed.
     */
    suspend fun makeMainIfNone(serverId: Long, sqlClient: SqlClient): Boolean {
        val current = mainServerId(sqlClient)

        if (current == serverId) {
            return false
        }

        if (current != NO_MAIN && databaseManager.serverDao.existsById(current, sqlClient)) {
            return false
        }

        setMain(serverId, sqlClient)

        return true
    }

    /**
     * After the main server was removed: the oldest approved server left takes its place, so a
     * install with servers always has a main one. Nothing left leaves it unset.
     */
    suspend fun promoteMainServer(sqlClient: SqlClient) {
        val next = databaseManager.serverDao.getAllByPermissionGranted(sqlClient).minByOrNull { it.id }

        setMain(next?.id ?: NO_MAIN, sqlClient)
    }

    private suspend fun mainServerId(sqlClient: SqlClient): Long =
        databaseManager.systemPropertyDao.getByOption(MAIN_OPTION, sqlClient)?.value?.toLongOrNull() ?: NO_MAIN

    private suspend fun setMain(serverId: Long, sqlClient: SqlClient) {
        if (databaseManager.systemPropertyDao.existsByOption(MAIN_OPTION, sqlClient)) {
            databaseManager.systemPropertyDao.update(MAIN_OPTION, serverId.toString(), sqlClient)
        } else {
            databaseManager.systemPropertyDao.add(SystemProperty(option = MAIN_OPTION, value = serverId.toString()), sqlClient)
        }
    }

    companion object {
        /** The `panel_config` option the selection is stored under. */
        const val OPTION = "selected_server"

        /** The `system_property` the main server is stored under, and its "none" value. */
        const val MAIN_OPTION = "main_server"
        const val NO_MAIN = -1L
    }
}
