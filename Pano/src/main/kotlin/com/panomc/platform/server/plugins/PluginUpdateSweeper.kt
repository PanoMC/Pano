package com.panomc.platform.server.plugins

import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.Server
import com.panomc.platform.server.alert.AlertManager
import io.vertx.core.Vertx
import io.vertx.kotlin.coroutines.dispatcher
import io.vertx.sqlclient.SqlClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.slf4j.Logger
import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.context.annotation.Lazy
import org.springframework.context.annotation.Scope
import org.springframework.stereotype.Component
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The once-a-day look at whether anybody's plugins have gone stale.
 *
 * The panel already answers this when somebody opens a plugins page, which is exactly the problem:
 * the servers that most need the answer belong to the people who do not open that page. So it is
 * asked on their behalf, and the answer arrives as a notification rather than as a badge nobody
 * sees.
 *
 * Everything about the shape of this is about not being a nuisance to three third-party APIs. One
 * server at a time, never in parallel; only servers that actually have tracked plugins, which is a
 * single indexed query and usually an empty one; through the same ten-minute catalogue cache
 * everything else uses, so a sweep right after somebody browsed costs almost nothing; and a tick
 * that is already running is skipped rather than queued. A daily sweep that hammers Modrinth would
 * get Pano's whole install base rate limited, and the operator would never find out why.
 */
@Lazy
@Component
@Scope(value = ConfigurableBeanFactory.SCOPE_SINGLETON)
class PluginUpdateSweeper(
    private val databaseManager: DatabaseManager,
    private val pluginUpdateService: PluginUpdateService,
    private val alertManager: AlertManager,
    private val vertx: Vertx,
    private val logger: Logger
) {
    private val started = AtomicBoolean(false)

    private val running = AtomicBoolean(false)

    /**
     * Arms the sweep. Idempotent.
     *
     * The first run waits a quarter of an hour, because a Pano that has just booted has nodes
     * reconnecting, plugins loading and an operator watching the log, and none of that is improved
     * by twenty outbound HTTP requests.
     */
    fun init() {
        if (!started.compareAndSet(false, true)) {
            return
        }

        vertx.setTimer(FIRST_RUN_DELAY_MS) { tick() }
        vertx.setPeriodic(INTERVAL_MS) { tick() }
    }

    private fun tick() {
        // A sweep that is somehow still going is not joined by a second one: the servers it has
        // not reached yet are reached on the next day, which is soon enough for this.
        if (!running.compareAndSet(false, true)) {
            return
        }

        CoroutineScope(vertx.dispatcher()).launch {
            try {
                sweep()
            } catch (e: Exception) {
                // Swallowed, or the timer dies with it and the feature silently stops.
                logger.warn("The plugin update sweep failed: ${e.message}")
            } finally {
                running.set(false)
            }
        }
    }

    /** Checks every managed server that has tracked plugins and raises an alert per server. */
    suspend fun sweep() {
        val sqlClient = databaseManager.getSqlClient()

        val serverIds = databaseManager.serverPluginInstallDao.getServerIdsWithRows(sqlClient)

        if (serverIds.isEmpty()) {
            return
        }

        var checked = 0
        var switchedOff = 0

        serverIds.forEach { serverId ->
            try {
                when (sweepServer(serverId, sqlClient)) {
                    Outcome.CHECKED -> checked++
                    Outcome.SWITCHED_OFF -> switchedOff++
                    Outcome.NOT_ELIGIBLE -> Unit
                }
            } catch (e: Exception) {
                logger.warn("Could not check server $serverId for plugin updates: ${e.message}")
            }
        }

        // Once per sweep rather than once per server: a switched-off server is a choice somebody
        // made, not news, and a line per server per day would be the nagging the switch turns off.
        if (switchedOff > 0) {
            logger.debug("Skipped $switchedOff server(s) with the automatic update check turned off.")
        }

        if (checked > 0) {
            logger.info("Checked $checked server(s) for plugin updates.")
        }
    }

    /** One server, and what became of it. */
    private suspend fun sweepServer(serverId: Long, sqlClient: SqlClient): Outcome {
        val server = databaseManager.serverDao.getById(serverId, sqlClient) ?: return Outcome.NOT_ELIGIBLE

        val eligibility = eligibilityOf(server)

        if (eligibility != Outcome.CHECKED) {
            return eligibility
        }

        val rows = databaseManager.serverPluginInstallDao.getByServerId(serverId, sqlClient)

        if (rows.isEmpty()) {
            return Outcome.NOT_ELIGIBLE
        }

        // No deadline: nobody is waiting, and a partial answer here would mean an alert that
        // under-reports rather than one that arrives late.
        val tracked = pluginUpdateService.tracked(server, rows)

        val outdated = tracked.filter { it.updateAvailable }

        if (outdated.isEmpty()) {
            return Outcome.CHECKED
        }

        alertManager.onPluginUpdates(
            server,
            outdated.map { it.projectName ?: it.filename },
            sqlClient
        )

        return Outcome.CHECKED
    }

    /** What the sweep did with one server. */
    enum class Outcome {
        /** Looked at, whether or not anything was outdated. */
        CHECKED,

        /** Left alone because its automatic update check is off (SM-69, §2.4.34). */
        SWITCHED_OFF,

        /** Not a server this sweep has anything to say about: gone, linked, or nothing tracked. */
        NOT_ELIGIBLE
    }

    companion object {
        /**
         * Whether the sweep may look at [server] at all, before a single row or catalogue call.
         *
         * A linked server has no tracked plugins to speak of (installs are managed-only), and a
         * server whose automatic update check is off is skipped whole: no identification, no
         * catalogue calls, no alert. The plugins page still answers when somebody opens it.
         */
        fun eligibilityOf(server: Server): Outcome = when {
            !server.isManaged -> Outcome.NOT_ELIGIBLE
            !server.settings.autoUpdateCheck -> Outcome.SWITCHED_OFF
            else -> Outcome.CHECKED
        }

        /** Long enough for a boot to finish being a boot. */
        const val FIRST_RUN_DELAY_MS = 15 * 60 * 1000L

        const val INTERVAL_MS = 24 * 60 * 60 * 1000L
    }
}
