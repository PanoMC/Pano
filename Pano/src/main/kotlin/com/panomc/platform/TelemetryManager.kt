package com.panomc.platform

import com.panomc.platform.config.ConfigManager
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.auth.panel.log.SentServerCommandLog
import com.panomc.platform.db.model.PanelActivityLog
import com.panomc.platform.db.model.SystemProperty
import com.panomc.platform.node.NodeStatus
import com.panomc.platform.server.ServerKind
import com.panomc.platform.setup.SetupManager
import com.panomc.platform.util.RegisterUtil
import com.panomc.platform.util.WebsiteUrlUtil
import io.vertx.core.Vertx
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.kotlin.coroutines.dispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.slf4j.Logger
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Lazy
import org.springframework.context.annotation.Scope
import org.springframework.stereotype.Component
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Sends a small usage-data heartbeat to the Pano API once a day so we know which versions,
 * themes and plugins are actually in use.
 *
 * Nothing here may ever block boot or take a request down: the whole job runs on a periodic
 * timer, every sub-lookup falls back to a default instead of throwing, and a failed send is
 * logged at debug and retried an hour later rather than warned about every minute.
 *
 * Opting out is a single config key, `telemetry.enabled = false`. The config is hot-reloaded, so
 * it is read fresh on every tick and takes effect without a restart.
 */
@Lazy
@Component
@Scope(value = ConfigurableBeanFactory.SCOPE_SINGLETON)
class TelemetryManager(
    private val vertx: Vertx,
    private val databaseManager: DatabaseManager,
    private val configManager: ConfigManager,
    private val setupManager: SetupManager,
    private val panoApiManager: PanoApiManager,
    private val pluginManager: PluginManager,
    private val applicationContext: ApplicationContext
) {
    @Autowired
    private lateinit var logger: Logger

    companion object {
        /** Anonymous, per-installation id. Lives in the database, never in config.conf, so a copied config does not clone it. */
        const val TELEMETRY_INSTALL_ID = "telemetry_install_id"

        /** Epoch millis of the last successful heartbeat. Absent means "never sent". */
        const val TELEMETRY_LAST_SENT_AT = "telemetry_last_sent_at"

        /**
         * Payload shape version. Bump it whenever a field is **renamed or removed**.
         *
         * Not for a new optional object: the receiving API pins this number exactly (anything
         * other than its `SUPPORTED_SCHEMA_VERSION` is rejected outright) while its body schema
         * allows properties it does not declare. So an additive field reaches an API that has not
         * been redeployed yet and is simply ignored there, whereas bumping the number would make
         * every install in the world stop reporting until that deploy happened.
         */
        private const val SCHEMA_VERSION = 1

        /** How far back the "last 24 hours" counters look. */
        private const val ACTIVITY_WINDOW_MS = 24L * 60L * 60L * 1000L

        private const val CHECK_INTERVAL_MS = 60_000L

        private const val SEND_INTERVAL_MS = 24L * 60L * 60L * 1000L

        /** Give the install time to settle (plugins started, themes registered) before reporting. */
        private const val STARTUP_GRACE_MS = 5L * 60L * 1000L

        /** After a failed send, wait this long instead of burning a whole day. */
        private const val RETRY_BACKOFF_MS = 60L * 60L * 1000L

        private const val MAX_FIELD_LENGTH = 128
        private const val MAX_URL_LENGTH = 512

        /** Build without a version, i.e. someone running from source. Never reported. */
        private const val LOCAL_BUILD_VERSION = "local-build"

        private const val DOCS_URL = "https://panomc.com/docs/platform/configuration/telemetry/"

        /** Derived from the log class, so a rename cannot silently zero the counter. */
        private val CONSOLE_COMMAND_LOG_TYPE = PanelActivityLog.typeOf(SentServerCommandLog::class.java)
    }

    private val uiManager: UIManager by lazy {
        applicationContext.getBean(UIManager::class.java)
    }

    private val sending = AtomicBoolean(false)
    private var timerId: Long? = null

    private var installId: String? = null

    /** Set after a failed attempt so the next tick backs off instead of retrying every minute. */
    private var nextAttemptAt: Long = 0

    private var loggedEnabledNotice = false

    fun init() {
        if (timerId != null) {
            return
        }

        timerId = vertx.setPeriodic(CHECK_INTERVAL_MS) {
            // Skip overlapping ticks if the previous send is still in flight (slow API, slow DB).
            if (!sending.compareAndSet(false, true)) {
                return@setPeriodic
            }

            CoroutineScope(vertx.dispatcher()).launch {
                try {
                    tick()
                } catch (t: Throwable) {
                    logger.debug("Usage data tick failed: {}", t.message)
                } finally {
                    sending.set(false)
                }
            }
        }

        logEnabledNotice()
    }

    /**
     * Everything that decides whether this install reports at all, timing aside. Read fresh every
     * tick because the config is hot-reloaded and setup may finish after boot.
     */
    private fun reportingAllowed(): Boolean {
        if (!setupManager.isSetupDone()) {
            return false
        }

        if (configManager.config.telemetry?.enabled == false) {
            return false
        }

        if (Main.IS_DEMO) {
            return false
        }

        if (Main.ENVIRONMENT == Main.Companion.EnvironmentType.DEVELOPMENT) {
            return false
        }

        return safely("version", LOCAL_BUILD_VERSION) { Main.VERSION } != LOCAL_BUILD_VERSION
    }

    private fun logEnabledNotice() {
        if (loggedEnabledNotice || !reportingAllowed()) {
            return
        }

        loggedEnabledNotice = true

        logger.info(
            "Usage data reporting is enabled. Disable with telemetry.enabled = false in config.conf — see {}",
            DOCS_URL
        )
    }

    private suspend fun tick() {
        // Setup can finish, or the key can be flipped back on, long after boot.
        logEnabledNotice()

        if (!reportingAllowed()) {
            return
        }

        val now = System.currentTimeMillis()

        if (now - Main.START_TIME < STARTUP_GRACE_MS) {
            return
        }

        if (now < nextAttemptAt) {
            return
        }

        // Null means the lookup itself failed: stay quiet rather than resend every minute.
        val lastSentAt = readLastSentAt() ?: return

        if (now - lastSentAt < SEND_INTERVAL_MS) {
            return
        }

        send()
    }

    private suspend fun send() {
        val payload = buildPayload()

        try {
            panoApiManager.sendTelemetry(payload)
        } catch (t: Throwable) {
            nextAttemptAt = System.currentTimeMillis() + RETRY_BACKOFF_MS

            logger.debug("Failed to send usage data, retrying in an hour: {}", t.message)

            return
        }

        nextAttemptAt = 0

        if (!writeLastSentAt(System.currentTimeMillis())) {
            // The heartbeat landed but we could not remember it. Back off so a broken database
            // cannot turn a daily heartbeat into a per-minute one.
            nextAttemptAt = System.currentTimeMillis() + RETRY_BACKOFF_MS
        }
    }

    /**
     * The anonymous installation id, generated once and persisted in `system_property`.
     * Returns an empty string if the database is unreachable.
     */
    suspend fun getInstallId(): String {
        installId?.let { return it }

        return try {
            val sqlClient = databaseManager.getSqlClient()
            val stored = databaseManager.systemPropertyDao.getByOption(TELEMETRY_INSTALL_ID, sqlClient)
            val storedValue = stored?.value?.trim()

            if (!storedValue.isNullOrEmpty()) {
                installId = storedValue

                return storedValue
            }

            val generated = UUID.randomUUID().toString()

            if (stored == null) {
                databaseManager.systemPropertyDao.add(
                    SystemProperty(option = TELEMETRY_INSTALL_ID, value = generated),
                    sqlClient
                )
            } else {
                databaseManager.systemPropertyDao.update(TELEMETRY_INSTALL_ID, generated, sqlClient)
            }

            installId = generated

            generated
        } catch (t: Throwable) {
            logger.debug("Failed to resolve the usage data install id: {}", t.message)

            ""
        }
    }

    /**
     * The exact JSON posted to the API. Also served by the panel preview endpoint so an owner can
     * see what leaves their server before deciding whether to keep reporting on.
     */
    suspend fun buildPayload(): JsonObject = JsonObject()
        .put("schemaVersion", SCHEMA_VERSION)
        .put("installId", getInstallId())
        .put("platform", buildPlatform())
        .put("runtime", buildRuntime())
        .put("site", buildSite())
        .put("theme", buildTheme())
        .put("plugins", buildPlugins())
        .put("usage", buildUsage())
        .put("serverManagement", buildServerManagement())
        .put("connected", safely("connected", false) { panoApiManager.isConnected() })
        .put("setupAt", resolveSetupAt())

    private fun buildPlatform(): JsonObject = JsonObject()
        .put("version", cut(safely("version", "") { Main.VERSION }, MAX_FIELD_LENGTH))
        .put("stage", cut(safely("stage", "") { Main.STAGE.name }, MAX_FIELD_LENGTH))
        .put("channel", cut(safely("channel", "") { configManager.config.releaseChannel.name }, MAX_FIELD_LENGTH))
        .put("uptimeSeconds", ((System.currentTimeMillis() - Main.START_TIME) / 1000L).coerceAtLeast(0L))

    private fun buildRuntime(): JsonObject {
        val runtime = Runtime.getRuntime()

        return JsonObject()
            .put(
                "javaVersion",
                cut(safely("javaVersion", "") { System.getProperty("java.version") ?: "" }, MAX_FIELD_LENGTH)
            )
            .put("os", cut(safely("os", "") { Main.OPERATING_SYSTEM.name }, MAX_FIELD_LENGTH))
            .put("arch", cut(safely("arch", "") { Main.ARCHITECTURE.name }, MAX_FIELD_LENGTH))
            .put("cpuCores", safely("cpuCores", 0) { runtime.availableProcessors() })
            .put("maxMemoryMb", safely("maxMemoryMb", 0) {
                (runtime.maxMemory() / (1024L * 1024L)).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
            })
    }

    private fun buildSite(): JsonObject {
        val websiteUrl = safely("websiteUrl", "") { WebsiteUrlUtil.normalize(configManager.config.websiteUrl) }

        return JsonObject()
            .put("websiteUrl", cut(websiteUrl, MAX_URL_LENGTH))
            .put(
                "domain",
                cut(safely("domain", "") { WebsiteUrlUtil.host(websiteUrl)?.lowercase() ?: "" }, MAX_FIELD_LENGTH)
            )
            .put("https", safely("https", false) { websiteUrl.startsWith("https://", ignoreCase = true) })
            .put("locale", cut(safely("locale", "") { configManager.config.locale }, MAX_FIELD_LENGTH))
    }

    /** Null when no theme is active — the server treats that as "the owner has not picked one yet". */
    private fun buildTheme(): JsonObject? = safely<JsonObject?>("theme", null) {
        val activeThemeId = uiManager.activeTheme.ifBlank { configManager.config.currentTheme }.trim()

        if (activeThemeId.isEmpty()) {
            return@safely null
        }

        val installedTheme = uiManager.installedThemeList.find { it.id == activeThemeId }

        JsonObject()
            .put("id", cut(activeThemeId, MAX_FIELD_LENGTH))
            .put("version", cut(installedTheme?.version ?: "", MAX_FIELD_LENGTH))
    }

    private fun buildPlugins(): JsonArray = safely("plugins", JsonArray()) {
        val plugins = JsonArray()

        pluginManager.plugins.forEach { plugin ->
            plugins.add(
                JsonObject()
                    .put("id", cut(plugin.pluginId, MAX_FIELD_LENGTH))
                    .put("version", cut(plugin.descriptor.version, MAX_FIELD_LENGTH))
                    .put("state", cut(plugin.pluginState.name, MAX_FIELD_LENGTH))
            )
        }

        plugins
    }

    private suspend fun buildUsage(): JsonObject {
        val usage = JsonObject()
            .put("users", 0L)
            .put("posts", 0L)
            .put("openTickets", 0L)
            .put("mcServers", 0L)
            .put("onlinePlayers", 0L)

        val sqlClient = try {
            databaseManager.getSqlClient()
        } catch (t: Throwable) {
            logger.debug("Failed to resolve usage counts for usage data: {}", t.message)

            return usage
        }

        usage.put("users", count("users") { databaseManager.userDao.count(sqlClient) })
        usage.put("posts", count("posts") { databaseManager.postDao.count(sqlClient) })
        usage.put("openTickets", count("openTickets") { databaseManager.ticketDao.countOfOpenTickets(sqlClient) })
        usage.put("mcServers", count("mcServers") { databaseManager.serverDao.count(sqlClient) })
        usage.put("onlinePlayers", count("onlinePlayers") { databaseManager.userDao.countOfOnline(sqlClient) })

        return usage
    }

    /**
     * What this install actually does with server management (SM-18, §2.4.12).
     *
     * Counts only: how many servers of each kind, how many nodes, and how much the two features
     * that cost real work — console commands and backups — were used in the last day. Nothing
     * here identifies a server, a node or a person; a name, an address or a software version
     * would say more about somebody's infrastructure than a usage counter needs to.
     *
     * The point of the two windowed counters is the difference between installed and used: a
     * platform with four managed servers nobody has opened a console on in a month is a very
     * different thing to plan around than one with four servers and a thousand commands a day.
     */
    private suspend fun buildServerManagement(): JsonObject {
        val serverManagement = JsonObject()
            .put(
                "usageMode",
                cut(safely("usageMode", "") { configManager.config.effectiveUsageMode.name }, MAX_FIELD_LENGTH)
            )
            .put("linkedServers", 0L)
            .put("managedServers", 0L)
            .put("nodes", 0L)
            .put("nodesOnline", 0L)
            .put("consoleCommandsLast24h", 0L)
            .put("backupsLast24h", 0L)

        val sqlClient = try {
            databaseManager.getSqlClient()
        } catch (t: Throwable) {
            logger.debug("Failed to resolve server management counts for usage data: {}", t.message)

            return serverManagement
        }

        val since = System.currentTimeMillis() - ACTIVITY_WINDOW_MS

        serverManagement.put("linkedServers", count("linkedServers") {
            databaseManager.serverDao.countByKind(ServerKind.LINKED, sqlClient)
        })
        serverManagement.put("managedServers", count("managedServers") {
            databaseManager.serverDao.countByKind(ServerKind.MANAGED, sqlClient)
        })
        serverManagement.put("nodes", count("nodes") { databaseManager.nodeDao.count(sqlClient) })
        serverManagement.put("nodesOnline", count("nodesOnline") {
            databaseManager.nodeDao.countByStatus(NodeStatus.ONLINE, sqlClient)
        })
        serverManagement.put("consoleCommandsLast24h", count("consoleCommandsLast24h") {
            databaseManager.panelActivityLogDao.countByTypeSince(CONSOLE_COMMAND_LOG_TYPE, since, sqlClient)
        })
        serverManagement.put("backupsLast24h", count("backupsLast24h") {
            databaseManager.serverBackupDao.countCreatedSince(since, sqlClient)
        })

        return serverManagement
    }

    /**
     * When the install was set up. There is no dedicated timestamp, so this uses the registration
     * date of the admin created by the setup wizard, tracked as `who_installed_user_id`.
     */
    private suspend fun resolveSetupAt(): Long = safelyAwait("setupAt", 0L) {
        val sqlClient = databaseManager.getSqlClient()

        val installerUserId = databaseManager.systemPropertyDao
            .getByOption(RegisterUtil.WHO_INSTALLED_USER_ID, sqlClient)
            ?.value
            ?.trim()
            ?.toLongOrNull()
            ?: return@safelyAwait 0L

        databaseManager.userDao.getById(installerUserId, sqlClient)?.registerDate ?: 0L
    }

    /** Null on a lookup failure, 0 when the install has never reported. */
    private suspend fun readLastSentAt(): Long? = try {
        val sqlClient = databaseManager.getSqlClient()

        databaseManager.systemPropertyDao.getByOption(TELEMETRY_LAST_SENT_AT, sqlClient)
            ?.value
            ?.trim()
            ?.toLongOrNull()
            ?: 0L
    } catch (t: Throwable) {
        logger.debug("Failed to read the last usage data timestamp: {}", t.message)

        null
    }

    private suspend fun writeLastSentAt(timestamp: Long): Boolean = try {
        val sqlClient = databaseManager.getSqlClient()

        if (databaseManager.systemPropertyDao.existsByOption(TELEMETRY_LAST_SENT_AT, sqlClient)) {
            databaseManager.systemPropertyDao.update(TELEMETRY_LAST_SENT_AT, timestamp.toString(), sqlClient)
        } else {
            databaseManager.systemPropertyDao.add(
                SystemProperty(option = TELEMETRY_LAST_SENT_AT, value = timestamp.toString()),
                sqlClient
            )
        }

        true
    } catch (t: Throwable) {
        logger.debug("Failed to store the last usage data timestamp: {}", t.message)

        false
    }

    private suspend fun count(field: String, block: suspend () -> Long): Long = safelyAwait(field, 0L, block)

    private fun <T> safely(field: String, fallback: T, block: () -> T): T = try {
        block()
    } catch (t: Throwable) {
        logger.debug("Failed to resolve usage data field {}: {}", field, t.message)

        fallback
    }

    private suspend fun <T> safelyAwait(field: String, fallback: T, block: suspend () -> T): T = try {
        block()
    } catch (t: Throwable) {
        logger.debug("Failed to resolve usage data field {}: {}", field, t.message)

        fallback
    }

    private fun cut(value: String, maxLength: Int): String {
        val trimmed = value.trim()

        return if (trimmed.length > maxLength) trimmed.substring(0, maxLength) else trimmed
    }
}
