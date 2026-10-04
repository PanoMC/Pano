package com.panomc.platform.server

import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.panel.PanelRealtimeHub
import com.panomc.platform.server.alert.AlertManager
import com.panomc.platform.server.plugins.PluginIdentificationService
import io.vertx.sqlclient.SqlClient
import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.context.annotation.Lazy
import org.springframework.context.annotation.Scope
import org.springframework.stereotype.Component

/**
 * Everything that has to happen when a server row disappears.
 *
 * Extracted because there are now two ways a server goes away: an admin deleting it from the panel
 * and a node reporting that it finished deleting the files. Both have to leave the same
 * consistent state behind — no dangling main-server pointer, no per-user selection pointing at a
 * row that no longer exists, no metrics history, no console buffer — and a second copy of that
 * list is a second chance to forget one of them.
 */
@Lazy
@Component
@Scope(value = ConfigurableBeanFactory.SCOPE_SINGLETON)
class ServerRemovalService(
    private val databaseManager: DatabaseManager,
    private val serverManager: ServerManager,
    private val panelRealtimeHub: PanelRealtimeHub,
    private val alertManager: AlertManager,
    private val pluginIdentificationService: PluginIdentificationService,
    private val serverStopReasonStore: ServerStopReasonStore,
    private val serverSelectionService: ServerSelectionService,
    private val activeTaskStore: ServerActiveTaskStore
) {
    /**
     * Removes the server with [serverId] and everything that pointed at it.
     *
     * The live socket is closed first: a plugin that is still connected would otherwise keep
     * writing rows for a server that no longer exists.
     */
    suspend fun remove(serverId: Long, sqlClient: SqlClient) {
        serverManager.closeConnection(serverId)

        serverStopReasonStore.remove(serverId)

        activeTaskStore.remove(serverId)

        val mainServerId = databaseManager.systemPropertyDao.getByOption("main_server", sqlClient)
            ?.value
            ?.toLongOrNull()

        if (mainServerId == serverId) {
            databaseManager.systemPropertyDao.update("main_server", "-1", sqlClient)
        }

        databaseManager.panelConfigDao.deleteByOptionAndValue("selected_server", serverId.toString(), sqlClient)

        databaseManager.serverPlayerDao.deleteByServerId(serverId, sqlClient)

        databaseManager.serverMetricDao.deleteByServerId(serverId, sqlClient)
        databaseManager.serverMetricDailyDao.deleteByServerId(serverId, sqlClient)

        // Tasks outlive the server on purpose: the delete task itself is still running when this
        // is called from the panel with force, and its own progress must not vanish mid-flight.
        // Backup rows go with the server: the archives live on the node and are removed with the
        // server directory, so a row that outlived it would point at nothing.
        databaseManager.serverBackupDao.deleteByServerId(serverId, sqlClient)

        // Schedules go with the server too, tasks first: nothing else would ever clean up the
        // step rows once their schedule is gone.
        databaseManager.serverScheduleDao.getAllByServerId(serverId, sqlClient).forEach { schedule ->
            databaseManager.serverScheduleTaskDao.deleteByScheduleId(schedule.id, sqlClient)
        }

        databaseManager.serverScheduleDao.deleteByServerId(serverId, sqlClient)

        databaseManager.serverAlertDao.deleteByServerId(serverId, sqlClient)

        // The provenance of jars in a directory that is being deleted with the server.
        databaseManager.serverPluginInstallDao.deleteByServerId(serverId, sqlClient)

        databaseManager.serverTaskDao.clearServerIdByServerId(serverId, sqlClient)

        databaseManager.serverDao.deleteById(serverId, sqlClient)

        // The main server is gone: the next one takes over rather than leaving the spot empty.
        if (mainServerId == serverId) {
            serverSelectionService.promoteMainServer(sqlClient)
        }

        serverManager.onServerDeleted(serverId)

        alertManager.onServerDeleted(serverId)

        pluginIdentificationService.onServerDeleted(serverId)

        panelRealtimeHub.notifyServerRemoved(serverId)
    }
}
