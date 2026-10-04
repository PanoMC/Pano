package com.panomc.platform.backup

import com.panomc.platform.Main
import com.panomc.platform.PlatformStateManager
import com.panomc.platform.PluginManager
import com.panomc.platform.api.PluginDatabaseManager
import com.panomc.platform.archive.instance.InstanceLayout
import com.panomc.platform.auth.panel.log.PanoBackupSystemLog
import com.panomc.platform.backup.remote.ConnectedAccount
import com.panomc.platform.backup.remote.MemoryRemoteStateStore
import com.panomc.platform.backup.remote.PanoHostClient
import com.panomc.platform.backup.remote.PanoIdentity
import com.panomc.platform.backup.remote.PanoRemoteBackupService
import com.panomc.platform.backup.remote.PassphraseFile
import com.panomc.platform.backup.remote.RemoteBackupState
import com.panomc.platform.backup.remote.RemoteStateStore
import com.panomc.platform.config.ConfigManager
import com.panomc.platform.config.PanoConfig
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.MariaDBManager
import com.panomc.platform.db.model.SystemProperty
import com.panomc.platform.maintenance.MaintenanceModeManager
import com.panomc.platform.setup.SetupManager
import io.vertx.core.Vertx
import io.vertx.core.json.JsonObject
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.kotlin.coroutines.dispatcher
import io.vertx.mysqlclient.MySQLConnectOptions
import io.vertx.mysqlclient.MySQLConnection
import io.vertx.sqlclient.SqlConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.Logger
import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Lazy
import org.springframework.context.annotation.Scope
import org.springframework.stereotype.Component
import java.io.File

/**
 * Wires [PanoBackupService] into the running platform: local store folder, settings in the
 * database, the schedule tick, and the setup-mode restore (setup-ui "restore from file").
 */
@Lazy
@Component
@Scope(value = ConfigurableBeanFactory.SCOPE_SINGLETON)
class PanoBackupManager(
    private val vertx: Vertx,
    private val logger: Logger,
    private val configManager: ConfigManager,
    private val setupManager: SetupManager,
    private val platformStateManager: PlatformStateManager,
    private val applicationContext: ApplicationContext
) {
    private val databaseManager by lazy { applicationContext.getBean(DatabaseManager::class.java) }
    private val maintenanceModeManager by lazy { applicationContext.getBean(MaintenanceModeManager::class.java) }
    private val pluginManager by lazy { applicationContext.getBean(PluginManager::class.java) }
    private val pluginDatabaseManager by lazy { applicationContext.getBean(PluginDatabaseManager::class.java) }
    private val mariaDBManager by lazy { applicationContext.getBean(MariaDBManager::class.java) }
    private val audit by lazy { applicationContext.getBean(PanoBackupAudit::class.java) }

    private val scope by lazy { CoroutineScope(vertx.dispatcher()) }

    val store by lazy { PanoBackupStore(File(System.getProperty("pano.backupsFolder", DEFAULT_FOLDER))) }

    /** The panel's service: maintenance mode, safety archive and a restart after a restore. */
    val service by lazy { PanoBackupService(store, PlatformHost(databaseOverride = null, panel = true), scope) }

    /** The Pano Host API base: `-Dpano.hostApiUrl`, env `PANO_HOST_API_URL`, else the config's `pano-api-url`. */
    fun hostApiUrl(): String =
        System.getProperty("pano.hostApiUrl")?.takeIf { it.isNotBlank() }
            ?: System.getenv("PANO_HOST_API_URL")?.takeIf { it.isNotBlank() }
            ?: configManager.config.panoApiUrl

    private fun instanceName(): String = configManager.config.websiteName.ifBlank { "Pano" }

    /** The platform connection (setup step 4 or the panel's "connect Pano account"): its token is the credential. */
    private fun connectionToken(): String? = configManager.config.panoAccount.accessToken.takeIf { it.isNotBlank() }

    private fun connectedAccount(): ConnectedAccount? {
        val account = configManager.config.panoAccount

        return if (account.accessToken.isBlank()) null else ConnectedAccount(account.username, account.platformId)
    }

    private val instanceIdLock = Mutex()

    @Volatile
    private var instanceId: String? = null

    /** `X-Pano-Instance-Id`: generated once and kept in the `pano-backup-instance-id` system property. */
    private suspend fun persistedInstanceId(): String {
        instanceId?.let { return it }

        return instanceIdLock.withLock {
            instanceId ?: run {
                val sqlClient = databaseManager.getSqlClient()
                val dao = databaseManager.systemPropertyDao
                val stored = dao.getByOption(RemoteBackupState.INSTANCE_ID_OPTION, sqlClient)?.value

                if (PanoHostClient.isValidInstanceId(stored)) {
                    stored!!
                } else {
                    val id = PanoHostClient.newInstanceId()

                    if (stored != null) dao.update(RemoteBackupState.INSTANCE_ID_OPTION, id, sqlClient)
                    else dao.add(SystemProperty(option = RemoteBackupState.INSTANCE_ID_OPTION, value = id), sqlClient)

                    id
                }
            }.also { instanceId = it }
        }
    }

    private val hostClient by lazy {
        PanoHostClient({ hostApiUrl() }, ::connectionToken, { PanoIdentity(persistedInstanceId(), instanceName()) })
    }

    /** Setup mode has no database yet: a throwaway instance id (a restore only downloads). */
    private val setupHostClient by lazy {
        val id = PanoHostClient.newInstanceId()

        PanoHostClient({ hostApiUrl() }, ::connectionToken, { PanoIdentity(id, instanceName()) })
    }

    /** Pano Backup + transfer of the running Pano over its platform connection; settings in the database. */
    val remote by lazy {
        PanoRemoteBackupService(
            client = hostClient,
            stateStore = DatabaseRemoteStateStore(),
            passphraseFile = PassphraseFile(File(store.directory, PassphraseFile.FILE_NAME)),
            backups = service,
            tempDir = { InstanceLayout.current(configManager.config).tempDir },
            account = ::connectedAccount,
            instanceName = ::instanceName,
            panoVersion = Main.VERSION,
            onScheduledSettled = { ok -> audit.system(PanoBackupSystemLog.ACTION_SCHEDULED_UPLOAD, ok) }
        )
    }

    /** setup-ui "import from Pano Backup": uses the account connected in setup step 4. */
    val setupRemote by lazy {
        PanoRemoteBackupService(
            client = setupHostClient,
            stateStore = MemoryRemoteStateStore(),
            passphraseFile = null,
            backups = service,
            tempDir = { InstanceLayout.current(configManager.config).tempDir },
            account = ::connectedAccount,
            instanceName = ::instanceName,
            panoVersion = Main.VERSION
        )
    }

    /** A managed MC server backup is READY: uploaded to Pano Backup when that server is selected. */
    fun onMcBackupReady(serverId: Long, backupId: String) {
        if (!setupManager.isSetupDone()) {
            return
        }

        scope.launch {
            try {
                val state = remote.state()

                if (serverId !in state.settings.mcServerIds || !remote.isConnected()) {
                    return@launch
                }

                val sources = applicationContext.getBean(McServerBackupSources::class.java)

                remote.onMcBackupReady(sources.source(serverId, backupId, databaseManager.getSqlClient()))
            } catch (e: Exception) {
                logger.warn("Could not queue MC server backup $backupId for Pano Backup: ${e.message}")
            }
        }
    }

    private inner class DatabaseRemoteStateStore : RemoteStateStore {
        override suspend fun load(): RemoteBackupState {
            val property = databaseManager.systemPropertyDao.getByOption(RemoteBackupState.OPTION, databaseManager.getSqlClient())

            return RemoteBackupState.parse(property?.value)
        }

        override suspend fun save(state: RemoteBackupState) {
            val sqlClient = databaseManager.getSqlClient()
            val dao = databaseManager.systemPropertyDao
            val value = state.toJson().encode()

            if (dao.existsByOption(RemoteBackupState.OPTION, sqlClient)) {
                dao.update(RemoteBackupState.OPTION, value, sqlClient)
            } else {
                dao.add(SystemProperty(option = RemoteBackupState.OPTION, value = value), sqlClient)
            }
        }
    }

    /** Setup mode has its own service (and job) because its target database comes from the request. */
    @Volatile
    var setupService: PanoBackupService? = null
        private set

    /** Starts the schedule tick; setup must be done. */
    fun start() {
        store.cleanParts()

        vertx.setPeriodic(TICK_INTERVAL_MS) {
            scope.launch {
                try {
                    tick(System.currentTimeMillis())
                } catch (e: Exception) {
                    logger.error("Failed to run the Pano Backup schedule", e)
                }
            }
        }
    }

    suspend fun tick(now: Long) {
        if (!setupManager.isSetupDone()) {
            return
        }

        val settings = getSettings()
        val lastScheduled = store.list().filter { it.tag == PanoBackupTag.SCHEDULED }.maxOfOrNull { it.createdAt }

        if (settings.isDue(lastScheduled, now)) {
            logger.info("Taking the scheduled Pano backup")

            // null = another backup job was running; the next tick tries again, nothing to record.
            val ok = try {
                service.tryCreate(null, PanoBackupTag.SCHEDULED)?.let { true }
            } catch (e: Exception) {
                logger.error("The scheduled Pano backup failed", e)

                false
            }

            if (ok != null) {
                audit.system(PanoBackupSystemLog.ACTION_SCHEDULED, ok)
            }
        }

        val pruned = store.prune(settings.keep, now)

        if (pruned.isNotEmpty()) {
            audit.system(PanoBackupSystemLog.ACTION_RETENTION, count = pruned.size)
        }

        try {
            remote.tick(now)
        } catch (e: Exception) {
            logger.error("Failed to run the Pano Backup upload schedule", e)
        }
    }

    suspend fun getSettings(): PanoBackupSettings {
        val property = databaseManager.systemPropertyDao.getByOption(PanoBackupSettings.OPTION, databaseManager.getSqlClient())

        return PanoBackupSettings.parse(property?.value)
    }

    suspend fun saveSettings(settings: PanoBackupSettings) {
        val sqlClient = databaseManager.getSqlClient()
        val dao = databaseManager.systemPropertyDao
        val value = settings.toJson().encode()

        if (dao.existsByOption(PanoBackupSettings.OPTION, sqlClient)) {
            dao.update(PanoBackupSettings.OPTION, value, sqlClient)
        } else {
            dao.add(SystemProperty(option = PanoBackupSettings.OPTION, value = value), sqlClient)
        }
    }

    /**
     * A service for one setup-mode restore into [database] (`host`, `name`, `username`, `password`;
     * null = the database configured in setup step 2, starting the portable MariaDB when that is
     * the configured type).
     */
    suspend fun prepareSetupService(database: JsonObject?): PanoBackupService {
        setupService?.let { if (it.isBusy()) return it }

        if (database == null && configManager.config.database.type == "portable") {
            mariaDBManager.start()
            mariaDBManager.createDefaultDatabase()
        }

        return PanoBackupService(store, PlatformHost(database, panel = false), scope).also { setupService = it }
    }

    /** The running Pano ([panel]) or setup mode with an explicit target database. */
    private inner class PlatformHost(@Volatile private var databaseOverride: JsonObject?, private val panel: Boolean) : PanoBackupHost {
        override val layout: InstanceLayout get() = InstanceLayout.current(configManager.config)

        override val panoVersion: String get() = Main.VERSION

        override fun dbPrefix(): String = configManager.config.database.prefix

        override fun targetConfig(): JsonObject {
            val config = JsonObject(configManager.config.toString())
            val databaseOverride = databaseOverride

            if (databaseOverride != null) {
                val database = config.getJsonObject("database")?.copy() ?: JsonObject()

                database.put("type", "mariadb")
                listOf("host", "name", "username", "password").forEach { database.put(it, databaseOverride.getString(it, "")) }
                config.put("database", database)
            }

            return config
        }

        override fun knownSchemeVersions(): Map<String, Int> {
            val versions = mutableMapOf<String, Int>()

            databaseManager.getLatestMigration()?.let { versions[CORE_SCHEME] = it.to }

            pluginManager.getActivePanoPlugins().forEach { plugin ->
                pluginDatabaseManager.getLatestMigration(plugin)?.let { versions[plugin.pluginId] = it.to }
            }

            return versions
        }

        override fun configVersion(): Int? = targetConfig().getInteger("config-version")

        // A dedicated connection, never one of the shared pool's: the dumper and the importer
        // change session state (time zone, sql_mode, foreign_key_checks) that must not leak.
        override suspend fun connect(): SqlConnection {
            val options = connectOptions(targetConfig().getJsonObject("database") ?: JsonObject())

            // The client throws this one inside its own context, where nobody is waiting: the connect
            // future would never complete and the restore would hold the lock for good.
            require(!options.host.isNullOrBlank()) { "No database host is configured." }

            return MySQLConnection.connect(vertx, options).coAwait()
        }

        override fun adoptDatabase(database: JsonObject): Boolean {
            // The running Pano always has its own; a portable database is managed by Pano itself.
            if (panel || configManager.config.database.type == "portable") {
                return false
            }

            databaseOverride = database.copy()

            return true
        }

        override suspend fun restoreFailed(code: String) {
            if (panel) {
                audit.writeFailedRestore(code)
            }
        }

        override suspend fun setMaintenance(enabled: Boolean): Boolean {
            if (!panel) {
                return false
            }

            val current: PanoConfig.Companion.MaintenanceConfig? = configManager.config.maintenance
            val maintenance = current ?: PanoConfig.Companion.MaintenanceConfig().also { configManager.config.maintenance = it }
            val previous = maintenance.enabled

            maintenance.enabled = enabled
            configManager.saveConfig()

            maintenanceModeManager.composeAndSavePage()
            maintenanceModeManager.invalidateAccessCache()

            return previous
        }

        override suspend fun applyConfig(config: JsonObject) {
            configManager.replaceConfig(config)
        }

        override suspend fun restoreApplied() {
            platformStateManager.restartRequired = true

            // The panel's restore entry goes into the database the restore just brought back.
            if (panel) {
                audit.writePendingRestore()
            }

            if (!autoRestart) {
                return
            }

            // Leaves the UI a moment to read the finished job before the process goes away.
            scope.launch {
                delay(RESTART_DELAY_MS)

                try {
                    platformStateManager.restart()
                } catch (e: Exception) {
                    logger.error("Could not restart Pano after the restore; restart it by hand", e)
                }
            }
        }
    }

    /** Off in tests and `--dev` source runs, where there is no jar to start again. */
    var autoRestart: Boolean = true

    companion object {
        const val DEFAULT_FOLDER = "pano-backups"
        const val CORE_SCHEME = "core"
        const val RESTART_DELAY_MS = 3000L

        private const val TICK_INTERVAL_MS = 10L * 60 * 1000

        fun connectOptions(database: JsonObject): MySQLConnectOptions {
            val address = database.getString("host", "")
            val options = MySQLConnectOptions()
                .setHost(address.substringBefore(':'))
                .setPort(address.substringAfter(':', "3306").toIntOrNull() ?: 3306)
                .setDatabase(database.getString("name", ""))
                .setUser(database.getString("username", ""))

            database.getString("password")?.takeIf { it.isNotEmpty() }?.let { options.password = it }

            return options
        }
    }
}
