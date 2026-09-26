package com.panomc.platform.backup

import com.panomc.platform.Main
import com.panomc.platform.PlatformStateManager
import com.panomc.platform.PluginManager
import com.panomc.platform.api.PluginDatabaseManager
import com.panomc.platform.archive.instance.InstanceLayout
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

    private val scope by lazy { CoroutineScope(vertx.dispatcher()) }

    val store by lazy { PanoBackupStore(File(System.getProperty("pano.backupsFolder", DEFAULT_FOLDER))) }

    /** The panel's service: maintenance mode, safety archive and a restart after a restore. */
    val service by lazy { PanoBackupService(store, PlatformHost(databaseOverride = null, panel = true), scope) }

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

            try {
                service.tryCreate(null, PanoBackupTag.SCHEDULED)
            } catch (e: Exception) {
                logger.error("The scheduled Pano backup failed", e)
            }
        }

        store.prune(settings.keep, now)
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
    private inner class PlatformHost(private val databaseOverride: JsonObject?, private val panel: Boolean) : PanoBackupHost {
        override val layout: InstanceLayout get() = InstanceLayout.current(configManager.config)

        override val panoVersion: String get() = Main.VERSION

        override fun dbPrefix(): String = configManager.config.database.prefix

        override fun targetConfig(): JsonObject {
            val config = JsonObject(configManager.config.toString())

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
        override suspend fun connect(): SqlConnection =
            MySQLConnection.connect(vertx, connectOptions(targetConfig().getJsonObject("database") ?: JsonObject())).coAwait()

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
