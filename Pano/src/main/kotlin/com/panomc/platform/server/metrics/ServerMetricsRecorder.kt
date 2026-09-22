package com.panomc.platform.server.metrics

import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.ServerMetric
import com.panomc.platform.server.ServerManager
import com.panomc.platform.setup.SetupManager
import com.panomc.platform.util.TimeUtil
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
 * Rolls the live metrics samples of every connected server into one database row per minute.
 *
 * Servers report every 10 seconds, which is the resolution the live view wants but six times more
 * history than any chart needs, so only the newest sample of each minute is persisted. Rows older
 * than the retention window are pruned once a day, which keeps the table at a few tens of
 * thousands of rows per server instead of growing forever.
 *
 * Same shape as `OnlinePlayerTracker`, including the setup guard: on a fresh install the timer is
 * already running while the database is still being set up.
 */
@Lazy
@Component
@Scope(value = ConfigurableBeanFactory.SCOPE_SINGLETON)
class ServerMetricsRecorder(
    private val vertx: Vertx,
    private val databaseManager: DatabaseManager,
    private val setupManager: SetupManager,
    private val serverManager: ServerManager
) {
    @Autowired
    private lateinit var logger: Logger

    private var lastPruneAt: Long = 0

    fun start() {
        logger.info("Started server metrics recorder.")

        vertx.setPeriodic(SAMPLE_INTERVAL_MS) {
            CoroutineScope(vertx.dispatcher()).launch {
                try {
                    tick()
                } catch (e: Exception) {
                    logger.error("Failed to record server metrics", e)
                }
            }
        }
    }

    private suspend fun tick() {
        if (!setupManager.isSetupDone()) {
            return
        }

        val now = System.currentTimeMillis()

        // Keyed by the sample and not by the plugin socket: a managed server with no Pano plugin
        // in it is reported by its node and would otherwise never get a row (§2.4.17 A).
        val serverIds = serverManager.getServerIdsWithMetrics()

        if (serverIds.isNotEmpty()) {
            val sqlClient = databaseManager.getSqlClient()

            serverIds.forEach { serverId ->
                val sample = serverManager.getLatestMetrics(serverId) ?: return@forEach

                // A sample that is older than the whole interval means the server stopped
                // reporting (old plugin, metrics turned off), and writing it again every minute
                // would draw a flat line that never happened.
                if (now - sample.t > STALE_SAMPLE_MS) {
                    return@forEach
                }

                databaseManager.serverMetricDao.add(
                    ServerMetric(
                        serverId = serverId,
                        ts = now,
                        tps = sample.tps1,
                        mspt = sample.mspt,
                        memUsed = sample.memUsed,
                        memMax = sample.memMax,
                        cpu = sample.cpu,
                        players = sample.playerCount,
                        source = sample.source,
                        // Null until somebody has walked the directory, which is most of the
                        // first five minutes after a server starts reporting (§2.4.18 A).
                        diskUsed = sample.diskUsed,
                        // Whatever the sample carries: the server's own traffic or, standing in for
                        // it, its node's (§2.4.22 A).
                        netRx = sample.netRx,
                        netTx = sample.netTx
                    ),
                    sqlClient
                )

                // The same minute, folded into today's row of the per-day rollup that outlives the
                // thirty days above (§2.4.24): peak and running average, one statement.
                databaseManager.serverMetricDailyDao.record(
                    serverId,
                    TimeUtil.startOfDay(now),
                    sample.playerCount,
                    sqlClient
                )
            }
        }

        if (now - lastPruneAt > PRUNE_INTERVAL_MS) {
            lastPruneAt = now

            try {
                databaseManager.serverMetricDao.deleteOlderThan(now - RETENTION_MS, databaseManager.getSqlClient())
                databaseManager.serverMetricDailyDao.deleteOlderThan(
                    now - DAILY_RETENTION_MS,
                    databaseManager.getSqlClient()
                )
            } catch (e: Exception) {
                logger.warn("Failed to prune server metric history", e)
            }
        }
    }

    companion object {
        private const val SAMPLE_INTERVAL_MS: Long = 60L * 1000L

        // Servers report every 10 seconds, so anything older than a minute is a server that went
        // quiet rather than one that is simply between samples.
        private const val STALE_SAMPLE_MS: Long = 60L * 1000L

        private const val PRUNE_INTERVAL_MS: Long = 1000L * 60L * 60L * 24L

        /** Retention window for the per-minute history, matching the plan in AGENT.md. */
        private const val RETENTION_MS: Long = 1000L * 60L * 60L * 24L * 30L

        /** Retention of the per-day rollup: a year and a month, so the Year view never runs dry. */
        private const val DAILY_RETENTION_MS: Long = 1000L * 60L * 60L * 24L * 400L
    }
}
