package com.panomc.platform.backup

import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.panel.log.PanoBackupActionLog
import com.panomc.platform.auth.panel.log.PanoBackupSystemLog
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.PanelActivityLog
import io.vertx.ext.web.RoutingContext
import org.slf4j.Logger
import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Lazy
import org.springframework.context.annotation.Scope
import org.springframework.stereotype.Component

/**
 * The activity log of the Pano backups (`/backups`): what a user did ([PanoBackupActionLog]) and
 * what the schedule did ([PanoBackupSystemLog]).
 *
 * A restore replaces the database, the activity log with it, so its entry cannot be written when
 * the request comes in: [deferRestore] keeps it and [writePendingRestore] writes it into the
 * restored database once the restore has been applied. A restore that fails is rolled back, and
 * the kept entry is dropped with the next one.
 *
 * Writing a log never fails the action it records: an error is only logged.
 */
@Lazy
@Component
@Scope(value = ConfigurableBeanFactory.SCOPE_SINGLETON)
class PanoBackupAudit(
    private val logger: Logger,
    private val applicationContext: ApplicationContext
) {
    private val authProvider by lazy { applicationContext.getBean(AuthProvider::class.java) }
    private val databaseManager by lazy { applicationContext.getBean(DatabaseManager::class.java) }

    @Volatile
    private var pendingRestore: PanoBackupActionLog? = null

    /** Records [action] by the signed-in user of [context]. */
    suspend fun log(context: RoutingContext, action: String, backupId: String? = null) {
        entry(context, action, backupId)?.let { write(it) }
    }

    /** Keeps a restore's entry until [writePendingRestore]: the restore replaces the log table. */
    suspend fun deferRestore(context: RoutingContext, action: String, backupId: String? = null) {
        pendingRestore = entry(context, action, backupId)
    }

    /** Called once a restore has been applied: its entry goes into the restored database. */
    suspend fun writePendingRestore() {
        val entry = pendingRestore ?: return

        pendingRestore = null

        write(entry)
    }

    /** Records what the schedule did; no user. */
    suspend fun system(action: String, ok: Boolean = true, count: Int = 0) {
        write(PanoBackupSystemLog(action, ok, count))
    }

    private suspend fun entry(context: RoutingContext, action: String, backupId: String?): PanoBackupActionLog? = try {
        val userId = authProvider.getUserIdFromRoutingContext(context)
        val username = databaseManager.userDao.getUsernameFromUserId(userId, databaseManager.getSqlClient()) ?: "#$userId"

        PanoBackupActionLog(userId, username, action, backupId)
    } catch (e: Exception) {
        logger.warn("Could not record the Pano backup action $action: ${e.message}")

        null
    }

    private suspend fun write(entry: PanelActivityLog) {
        try {
            databaseManager.panelActivityLogDao.add(entry, databaseManager.getSqlClient())
        } catch (e: Exception) {
            logger.warn("Could not write the Pano backup activity log: ${e.message}")
        }
    }
}
