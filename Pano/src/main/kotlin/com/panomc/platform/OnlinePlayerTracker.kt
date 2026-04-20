package com.panomc.platform

import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.setup.SetupManager
import com.panomc.platform.util.DateUtil
import io.vertx.core.Vertx
import io.vertx.kotlin.coroutines.dispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.slf4j.Logger
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.context.annotation.Lazy
import org.springframework.context.annotation.Scope
import org.springframework.stereotype.Component

/**
 * Periodically snapshots the current online player count into the `online_player_history`
 * table so that the statistics panel can draw historical day-by-day online player charts.
 *
 * Running max/avg is stored per day; the job also prunes very old data past the retention
 * window so the table does not grow indefinitely.
 */
@Lazy
@Component
@Scope(value = ConfigurableBeanFactory.SCOPE_SINGLETON)
class OnlinePlayerTracker(
    private val vertx: Vertx,
    private val databaseManager: DatabaseManager,
    private val setupManager: SetupManager,
) {
    @Autowired
    private lateinit var logger: Logger

    companion object {
        // Sample the online count every minute.
        private const val SAMPLE_INTERVAL_MS: Long = 60L * 1000L

        // Keep history for roughly 6 months. Anything older gets pruned.
        private const val RETENTION_MS: Long = 1000L * 60L * 60L * 24L * 180L
    }

    private var lastPruneAt: Long = 0

    fun start() {
        logger.info("Started online player tracker.")

        vertx.setPeriodic(SAMPLE_INTERVAL_MS) {
            CoroutineScope(vertx.dispatcher()).launch {
                try {
                    tick()
                } catch (e: Exception) {
                    logger.error("Failed to record online player snapshot", e)
                }
            }
        }
    }

    private suspend fun tick() {
        if (!setupManager.isSetupDone()) {
            return
        }

        val sqlClient = databaseManager.getSqlClient()
        val currentOnline = databaseManager.userDao.countOfOnline(sqlClient)
        val today = DateUtil.getTodayInMillis()

        databaseManager.onlinePlayerHistoryDao.recordSample(today, currentOnline, sqlClient)

        val now = System.currentTimeMillis()
        // Prune at most once per day
        if (now - lastPruneAt > 1000L * 60L * 60L * 24L) {
            try {
                databaseManager.onlinePlayerHistoryDao.deleteOlderThan(now - RETENTION_MS, sqlClient)
            } catch (e: Exception) {
                logger.warn("Failed to prune online player history", e)
            }
            lastPruneAt = now
        }
    }
}
