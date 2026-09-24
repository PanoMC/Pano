package com.panomc.platform.server.alert

import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.panel.permission.ManageServersPermission
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.Node
import com.panomc.platform.db.model.Server
import com.panomc.platform.db.model.ServerAlert
import com.panomc.platform.db.model.SystemProperty
import com.panomc.platform.mail.MailManager
import com.panomc.platform.mail.templates.ServerAlertMail
import com.panomc.platform.notification.NotificationManager
import com.panomc.platform.notification.PanelUserNotificationType
import com.panomc.platform.notification.type.panel.BackupFailedNotification
import com.panomc.platform.notification.type.panel.DiskLowNotification
import com.panomc.platform.notification.type.panel.NodeOfflineNotification
import com.panomc.platform.notification.type.panel.PluginUpdatesNotification
import com.panomc.platform.notification.type.panel.ScheduleFailedNotification
import com.panomc.platform.notification.type.panel.ServerCrashedNotification
import com.panomc.platform.server.console.ServerCrashReason
import com.panomc.platform.notification.type.panel.TpsLowNotification
import io.vertx.sqlclient.SqlClient
import org.slf4j.Logger
import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.context.annotation.Lazy
import org.springframework.context.annotation.Scope
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

/**
 * The one place a thing that went wrong becomes something a person is told about.
 *
 * Every hook feeds through here rather than raising its own notification, for three reasons that
 * only work when they are in one place. Cooldowns: the signals these are derived from repeat every
 * few seconds, so without them an alert is a stream. Settings: an operator who turned a kind off
 * expects it off everywhere, not off in the two call sites somebody remembered. And history: a
 * notification gets dismissed, while `server_alert` is what can still answer "how often did this
 * happen" next month.
 *
 * Nothing here is allowed to fail its caller. Every hook is on a path that matters more than the
 * alert does — a metrics frame, a task finishing, a node disconnecting — and an alert that throws
 * would take that with it.
 */
@Lazy
@Component
@Scope(value = ConfigurableBeanFactory.SCOPE_SINGLETON)
class AlertManager(
    private val databaseManager: DatabaseManager,
    private val notificationManager: NotificationManager,
    private val mailManager: MailManager,
    private val authProvider: AuthProvider,
    private val logger: Logger
) {
    private val cooldowns = AlertCooldownTracker()

    private val tpsWindow = TpsLowWindow()

    /** Whether this build can send alert e-mail at all, which the settings page reports. */
    val isEmailAvailable: Boolean by lazy { ServerAlertMail.isAvailable() }

    /** The stored switch grid, or the defaults when nothing has been saved. */
    suspend fun settings(sqlClient: SqlClient): Map<ServerAlertKind, AlertSetting> =
        AlertSettings.parse(databaseManager.systemPropertyDao.getByOption(AlertSettings.PROPERTY, sqlClient)?.value)

    /** Stores a new switch grid, creating the row the first time. */
    suspend fun updateSettings(settings: Map<ServerAlertKind, AlertSetting>, sqlClient: SqlClient) {
        val encoded = AlertSettings.toJson(settings).encode()

        if (databaseManager.systemPropertyDao.existsByOption(AlertSettings.PROPERTY, sqlClient)) {
            databaseManager.systemPropertyDao.update(AlertSettings.PROPERTY, encoded, sqlClient)

            return
        }

        databaseManager.systemPropertyDao.add(
            SystemProperty(option = AlertSettings.PROPERTY, value = encoded),
            sqlClient
        )
    }

    // ------------------------------------------------------------------------------------ hooks

    /**
     * A managed server's process died on its own.
     *
     * [reason] is the console line that explains it, when there is one: the alert an admin reads
     * at seven in the morning should say `UnsupportedClassVersionError`, not only "exit code 1".
     */
    suspend fun onServerCrashed(server: Server, exitCode: Int?, reason: String?, sqlClient: SqlClient) {
        val name = displayName(server)

        val cleaned = ServerCrashReason.clean(reason)

        raise(
            subject = "server:${server.id}",
            serverId = server.id,
            server = server,
            nodeId = server.nodeId,
            serverName = name,
            message = ServerAlertMessage.serverCrashed(name, exitCode, cleaned),
            notification = ServerCrashedNotification(server.id, name, exitCode, cleaned),
            link = "/servers/${server.id}",
            sqlClient = sqlClient
        )
    }

    /** A node stopped answering, so nothing it runs can be reached. */
    suspend fun onNodeOffline(node: Node, sqlClient: SqlClient) {
        val serverCount = try {
            databaseManager.serverDao.getAllByNodeId(node.id, sqlClient).size
        } catch (_: Exception) {
            0
        }

        raise(
            subject = "node:${node.id}",
            serverId = null,
            nodeId = node.id,
            message = ServerAlertMessage.nodeOffline(node.name, serverCount),
            notification = NodeOfflineNotification(
                nodeId = node.id,
                nodeName = node.name,
                serverCount = serverCount
            ),
            link = "/servers/nodes",
            sqlClient = sqlClient
        )
    }

    /** A node answered again, so the next outage is reported immediately rather than after a cooldown. */
    suspend fun onNodeOnline(nodeId: Long, sqlClient: SqlClient) {
        cooldowns.clear(ServerAlertKind.NODE_OFFLINE, "node:$nodeId")

        try {
            databaseManager.serverAlertDao.resolveOpen(
                ServerAlertKind.NODE_OFFLINE.name,
                null,
                nodeId,
                System.currentTimeMillis(),
                sqlClient
            )
        } catch (e: Exception) {
            logger.warn("Could not close the open NODE_OFFLINE alerts of node $nodeId: ${e.message}")
        }
    }

    /** A backup task ended in FAILED, which means tonight has no copy of that world. */
    suspend fun onBackupFailed(server: Server, error: String?, sqlClient: SqlClient) {
        val name = displayName(server)

        raise(
            subject = "server:${server.id}",
            serverId = server.id,
            server = server,
            nodeId = server.nodeId,
            serverName = name,
            message = ServerAlertMessage.backupFailed(name, error),
            notification = BackupFailedNotification(
                serverId = server.id,
                serverName = name,
                nodeId = server.nodeId,
                nodeName = nodeNameOf(server.nodeId, sqlClient),
                error = error?.take(MAX_ERROR_LENGTH)
            ),
            link = "/servers/${server.id}",
            sqlClient = sqlClient
        )
    }

    /** A node's data disk is nearly full. Evaluated on every metrics frame, reported rarely. */
    suspend fun onNodeMetrics(node: Node, diskUsed: Long, diskTotal: Long, sqlClient: SqlClient) {
        if (!AlertThresholds.isDiskLow(diskUsed, diskTotal)) {
            cooldowns.clear(ServerAlertKind.DISK_LOW, "node:${node.id}")

            return
        }

        val percent = (AlertThresholds.diskRatio(diskUsed, diskTotal) * 100).roundToInt()

        raise(
            subject = "node:${node.id}",
            serverId = null,
            nodeId = node.id,
            message = ServerAlertMessage.diskLow(node.name, percent),
            notification = DiskLowNotification(
                nodeId = node.id,
                nodeName = node.name,
                usedPercent = percent,
                freeBytes = (diskTotal - diskUsed).coerceAtLeast(0)
            ),
            link = "/servers/nodes",
            sqlClient = sqlClient
        )
    }

    /**
     * A tick-rate sample arrived. Only a run of bad ones raises anything.
     *
     * Called for every sample of every server, ten seconds apart, so this has to be cheap and has
     * to say nothing almost every time.
     */
    suspend fun onServerTps(server: Server, tps: Double?, sqlClient: SqlClient) {
        if (!tpsWindow.offer(server.id, tps)) {
            return
        }

        val name = displayName(server)
        val reading = String.format(java.util.Locale.ROOT, "%.1f", tps ?: 0.0)

        raise(
            subject = "server:${server.id}",
            serverId = server.id,
            server = server,
            nodeId = server.nodeId,
            serverName = name,
            message = ServerAlertMessage.tpsLow(name, reading),
            notification = TpsLowNotification(
                serverId = server.id,
                serverName = name,
                nodeId = server.nodeId,
                nodeName = nodeNameOf(server.nodeId, sqlClient),
                tps = reading
            ),
            link = "/servers/${server.id}",
            sqlClient = sqlClient
        )
    }

    /** A schedule ran and something in it did not work. */
    suspend fun onScheduleFailed(server: Server, scheduleName: String, error: String?, sqlClient: SqlClient) {
        val name = displayName(server)

        raise(
            subject = "server:${server.id}:$scheduleName",
            serverId = server.id,
            server = server,
            nodeId = server.nodeId,
            serverName = name,
            message = ServerAlertMessage.scheduleFailed(name, scheduleName, error),
            notification = ScheduleFailedNotification(
                serverId = server.id,
                serverName = name,
                nodeId = server.nodeId,
                nodeName = nodeNameOf(server.nodeId, sqlClient),
                scheduleName = scheduleName,
                error = error?.take(MAX_ERROR_LENGTH)
            ),
            link = "/servers/${server.id}",
            sqlClient = sqlClient
        )
    }

    /**
     * A server is running plugins with newer builds available.
     *
     * Raised by the nightly sweep only. Nothing here watches a plugin site in real time, and
     * nothing should: this is the one alert kind where the right cadence is "once a day, if it is
     * still true".
     */
    suspend fun onPluginUpdates(server: Server, names: List<String>, sqlClient: SqlClient) {
        // The sweep already skips these (SM-69); checked here too so no other caller can nag
        // about a server whose owner switched the check off.
        if (names.isEmpty() || !server.settings.autoUpdateCheck) {
            return
        }

        val name = displayName(server)
        val shown = names.take(MAX_PLUGIN_NAMES)

        raise(
            subject = "server:${server.id}",
            serverId = server.id,
            server = server,
            nodeId = server.nodeId,
            serverName = name,
            message = ServerAlertMessage.pluginUpdates(name, names.size, shown),
            notification = PluginUpdatesNotification(
                serverId = server.id,
                serverName = name,
                count = names.size,
                names = shown
            ),
            link = "/servers/${server.id}/plugins",
            sqlClient = sqlClient
        )
    }

    /** Drops everything remembered about a server that no longer exists. */
    fun onServerDeleted(serverId: Long) {
        tpsWindow.forget(serverId)
        cooldowns.forget("server:$serverId")
    }

    /** Drops everything remembered about a node that no longer exists. */
    fun onNodeDeleted(nodeId: Long) {
        cooldowns.forget("node:$nodeId")
    }

    // ------------------------------------------------------------------------------------ core

    /**
     * Records one alert and tells everyone who can act on it.
     *
     * The cooldown is checked before anything is written, so a suppressed alert costs one map
     * lookup rather than a row and a fan-out -- plus, for the first raise of a kind and subject
     * since boot, one indexed read of the newest stored row, so a restart does not reset it. Everything after that point is best effort: the
     * caller is on a hot path and the alert is the least important thing happening on it.
     */
    private suspend fun raise(
        subject: String,
        serverId: Long?,
        /** The server the alert is about, whose `settings.alerts` can override the platform switch. */
        server: Server? = null,
        nodeId: Long?,
        serverName: String? = null,
        message: ServerAlertMessage,
        notification: PanelUserNotificationType,
        link: String,
        sqlClient: SqlClient
    ) {
        val kind = message.kind

        try {
            val setting = settings(sqlClient)[kind] ?: AlertSetting(enabled = true, email = false)

            if (!isEnabledFor(kind, setting, server)) {
                return
            }

            val now = System.currentTimeMillis()

            if (!cooldowns.tryRaise(kind, subject, now) { storedLastRaise(kind, subject, sqlClient) }) {
                return
            }

            databaseManager.serverAlertDao.add(
                ServerAlert(
                    kind = kind,
                    serverId = serverId,
                    nodeId = nodeId,
                    message = message.english.take(MAX_MESSAGE_LENGTH)
                ),
                sqlClient
            )

            notificationManager.sendNotificationToAllWithPermission(
                notification,
                ManageServersPermission(),
                sqlClient
            )

            logger.warn("Alert ${kind.name}: ${message.english}")

            if (setting.email && isEmailAvailable) {
                sendEmails(message, link, serverName, nodeId, now, sqlClient)
            }
        } catch (e: Exception) {
            logger.warn("Could not raise a ${kind.name} alert: ${e.message}")
        }
    }

    /**
     * Sends the alert to the same people the notification went to, each in their own language.
     *
     * The message is still keys and values at this point on purpose: [ServerAlertMail] renders it
     * once per recipient against the locale that recipient reads Pano in.
     *
     * One failed address must not stop the rest, and none of them may stop the alert itself: the
     * notification is already written by the time this runs, so a broken SMTP server costs the
     * e-mail and nothing else.
     */
    /**
     * Whether [kind] is raised for [server]: that server's own switch when it has one (a
     * server-scoped kind only), the platform-wide [setting] otherwise. E-mail stays platform-wide.
     */
    private fun isEnabledFor(kind: ServerAlertKind, setting: AlertSetting, server: Server?): Boolean =
        resolveEnabled(kind, setting.enabled, server?.settings?.alerts)

    private suspend fun sendEmails(
        message: ServerAlertMessage,
        link: String,
        serverName: String?,
        nodeId: Long?,
        occurredAt: Long,
        sqlClient: SqlClient
    ) {
        val kind = message.kind

        val recipients = try {
            recipientIds(sqlClient)
        } catch (e: Exception) {
            logger.warn("Could not work out who to e-mail about a ${kind.name} alert: ${e.message}")

            return
        }

        val nodeName = nodeNameOf(nodeId, sqlClient)
        val formattedOccurredAt = formatTimestamp(occurredAt)

        recipients.forEach { userId ->
            try {
                mailManager.sendMail(
                    sqlClient,
                    userId,
                    ServerAlertMail(
                        message = message,
                        link = link,
                        serverName = serverName,
                        nodeName = nodeName,
                        occurredAt = formattedOccurredAt
                    )
                )
            } catch (e: Exception) {
                logger.warn("Could not e-mail user $userId about a ${kind.name} alert: ${e.message}")
            }
        }
    }

    /**
     * When this alert was last raised according to `server_alert`, for the first raise of a key
     * after boot (SM-69, §2.4.34). Null when the subject has no stored shape, there is no row, the
     * newest row was resolved, or the table could not be read -- an unreadable history must not
     * cost the alert itself, so it falls back to the in-memory cooldown alone.
     */
    private suspend fun storedLastRaise(kind: ServerAlertKind, subject: String, sqlClient: SqlClient): Long? {
        val stored = AlertSubject.parse(subject) ?: return null

        return try {
            AlertSubject.lastRaiseOf(
                databaseManager.serverAlertDao.getLatestByKindAndSubject(
                    kind.name,
                    stored.serverId,
                    stored.nodeId,
                    sqlClient
                )
            )
        } catch (e: Exception) {
            logger.debug("Could not read the last ${kind.name} alert about $subject: ${e.message}")

            null
        }
    }

    private suspend fun recipientIds(sqlClient: SqlClient): List<Long> {
        val admins = authProvider.getAdminList(sqlClient)

        val adminIds = databaseManager.userDao.getIdsByListOfUsername(admins, sqlClient).map { it.value }

        return adminIds.distinct()
    }

    private suspend fun nodeNameOf(nodeId: Long?, sqlClient: SqlClient): String? {
        val id = nodeId ?: return null

        return try {
            databaseManager.nodeDao.getById(id, sqlClient)?.name
        } catch (_: Exception) {
            null
        }
    }

    private fun displayName(server: Server) = server.customName ?: server.name

    /** The alert's moment, written the way the other mails write one, in the server's own zone. */
    private fun formatTimestamp(timestamp: Long): String =
        LocalDateTime.ofInstant(Instant.ofEpochMilli(timestamp), ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"))

    companion object {
        /**
         * The per-server override rule, pure so it can be asserted: a server-scoped [kind] with an
         * entry in [overrides] uses it, anything else uses [platformEnabled].
         */
        fun resolveEnabled(kind: ServerAlertKind, platformEnabled: Boolean, overrides: Map<String, Boolean>?): Boolean {
            if (!kind.serverScoped) {
                return platformEnabled
            }

            return overrides?.get(kind.name) ?: platformEnabled
        }

        private const val MAX_MESSAGE_LENGTH = 1000
        private const val MAX_ERROR_LENGTH = 500

        /** How many plugin names an alert names before it just says how many there are. */
        private const val MAX_PLUGIN_NAMES = 5
    }
}
