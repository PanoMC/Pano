package com.panomc.platform.backup

import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.panel.log.PanoBackupActionLog
import com.panomc.platform.auth.panel.log.PanoBackupSystemLog
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.PanelActivityLog
import com.panomc.platform.token.AuthenticationTokenType
import com.panomc.platform.token.TokenProvider
import io.vertx.ext.web.RoutingContext
import java.security.MessageDigest
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
 * restored database once the restore has been applied. A restore that is rejected, fails or is
 * rolled back leaves the database as it was, and [writeFailedRestore] records it with its error.
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
    private val tokenProvider by lazy { applicationContext.getBean(TokenProvider::class.java) }

    @Volatile
    private var pendingRestore: Pending? = null

    private class Pending(val userId: Long, val username: String, val action: String, val backupId: String?, val token: String?)

    /** The session that started the last restore; see [isRestoreStarter]. */
    @Volatile
    private var restoreStarterToken: String? = null

    /**
     * Whether [context] carries the session that started the last restore. A restore brings back
     * the session table of the backup, so the admin who started it may no longer be signed in by
     * the time it ends; they are still let to read how their own restore went.
     */
    fun isRestoreStarter(context: RoutingContext): Boolean {
        val starter = restoreStarterToken ?: return false
        val token = authProvider.getTokenFromRoutingContext(context) ?: return false

        return MessageDigest.isEqual(starter.toByteArray(), token.toByteArray())
    }

    /** Records [action] by the signed-in user of [context]. */
    suspend fun log(context: RoutingContext, action: String, backupId: String? = null) {
        pending(context, action, backupId)?.let { write(PanoBackupActionLog(it.userId, it.username, it.action, it.backupId)) }
    }

    /**
     * Who restores what, looked up before the restore is started: once it runs it may drop the
     * tables this reads. Hand the result to [deferRestore] right after the job was started.
     */
    suspend fun restoreEntry(context: RoutingContext, action: String, backupId: String? = null): Any? =
        pending(context, action, backupId)

    /**
     * Keeps a restore's [entry] (from [restoreEntry]) until [writePendingRestore] or
     * [writeFailedRestore]. Not suspending on purpose: called straight after the job was started,
     * it is in place before the job can finish.
     */
    fun deferRestore(entry: Any?) {
        pendingRestore = entry as? Pending
        restoreStarterToken = pendingRestore?.token
    }

    /** Keeps a restore's entry until [writePendingRestore]: the restore replaces the log table. */
    suspend fun deferRestore(context: RoutingContext, action: String, backupId: String? = null) {
        deferRestore(pending(context, action, backupId))
    }

    /** Called once a restore has been applied: its entry goes into the restored database. */
    suspend fun writePendingRestore() {
        val entry = pendingRestore ?: return

        pendingRestore = null

        write(PanoBackupActionLog(entry.userId, entry.username, entry.action, entry.backupId))
        carrySession(entry)
    }

    /**
     * Keeps the admin who restored signed in: their session was created after the backup was
     * taken, so the restored session table does not know it. It is put back only when the restored
     * Pano has the same account (id and username) and still signs with the same key; a backup of
     * another Pano, or of a time before the account existed, signs them out as before.
     */
    private suspend fun carrySession(entry: Pending) {
        val token = entry.token ?: return

        try {
            val sqlClient = databaseManager.getSqlClient()

            if (databaseManager.userDao.getUsernameFromUserId(entry.userId, sqlClient) != entry.username) {
                return
            }

            val jwt = tokenProvider.parseToken(token)

            if (jwt.subject != entry.userId.toString() ||
                databaseManager.tokenDao.existsByTokenAndType(token, AuthenticationTokenType, sqlClient)
            ) {
                return
            }

            tokenProvider.saveToken(token, jwt.subject, AuthenticationTokenType, jwt.expiresAt.time, sqlClient)
        } catch (e: Exception) {
            logger.info("The session that started the restore does not carry over: ${e.message}")
        }
    }

    /** Called when a restore was rejected, failed or was rolled back: recorded with its error [code]. */
    suspend fun writeFailedRestore(code: String) {
        val entry = pendingRestore ?: return

        pendingRestore = null

        write(PanoBackupActionLog(entry.userId, entry.username, entry.action, entry.backupId, error = code))
    }

    /** Records what the schedule did; no user. */
    suspend fun system(action: String, ok: Boolean = true, count: Int = 0) {
        write(PanoBackupSystemLog(action, ok, count))
    }

    private suspend fun pending(context: RoutingContext, action: String, backupId: String?): Pending? = try {
        val userId = authProvider.getUserIdFromRoutingContext(context)
        val username = databaseManager.userDao.getUsernameFromUserId(userId, databaseManager.getSqlClient()) ?: "#$userId"

        Pending(userId, username, action, backupId, authProvider.getTokenFromRoutingContext(context))
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
