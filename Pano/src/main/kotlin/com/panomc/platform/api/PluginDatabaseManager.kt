package com.panomc.platform.api

import com.panomc.platform.Main
import com.panomc.platform.PluginManager
import com.panomc.platform.api.event.PluginLifecycleListener
import com.panomc.platform.db.Dao
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.DatabaseMigration
import com.panomc.platform.db.model.SchemeVersion
import com.panomc.platform.setup.SetupManager
import io.vertx.sqlclient.SqlClient
import org.slf4j.Logger
import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.context.annotation.Lazy
import org.springframework.context.annotation.Scope
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap

@Component
@Scope(value = ConfigurableBeanFactory.SCOPE_SINGLETON)
class PluginDatabaseManager(
    @get:Lazy private val databaseManager: DatabaseManager,
    @get:Lazy private val logger: Logger,
    private val pluginManager: PluginManager,
    @get:Lazy private val setupManager: SetupManager,
    private val main: Main
): PluginLifecycleListener {
    init {
        pluginManager.addLifecycleListener(this)
    }

    // Concurrent: written on plugin load / unload, read from backup threads.
    private val tables = ConcurrentHashMap<PanoPlugin, MutableList<Dao<*>>>()
    private val migrations = ConcurrentHashMap<PanoPlugin, MutableList<DatabaseMigration>>()

    override suspend fun onPluginLoad(plugin: PanoPlugin) {
        val daoList =
            plugin.pluginBeanContext.getBeansOfType(Dao::class.java).values.map { it as Dao }.toMutableList()
        val dbMigrations =
            plugin.pluginBeanContext.getBeansOfType(DatabaseMigration::class.java).values.map { it as DatabaseMigration }.toMutableList()

        tables[plugin] = daoList
        migrations[plugin] = dbMigrations
    }

    // Unload runs on disable, update and delete (after onUninstall), and drops the last reference to the
    // plugin's DAOs, so its classloader and Spring context can be collected. A re-enable loads it again.
    override suspend fun onPluginUnload(plugin: PanoPlugin) {
        forget(plugin)
    }

    override suspend fun onPluginUninstall(plugin: PanoPlugin) {
        forget(plugin)
    }

    private fun forget(plugin: PanoPlugin) {
        tables.remove(plugin)
        migrations.remove(plugin)
    }

    internal fun getLatestMigration(plugin: PanoPlugin) = migrations[plugin]?.maxByOrNull { it.to }

    private suspend fun initSchemeVersion(plugin: PanoPlugin, sqlClient: SqlClient) {
        val lastSchemeVersion = databaseManager.schemeVersionDao.getLastSchemeVersion(plugin.pluginId, sqlClient)

        val latestMigration = getLatestMigration(plugin)

        if (lastSchemeVersion != null) {
            return
        }

        if (latestMigration == null) {
            databaseManager.schemeVersionDao.add(
                sqlClient,
                SchemeVersion(
                    pluginId = plugin.pluginId,
                    key = "1",
                    extra = "Init ${plugin.pluginId}"
                )
            )

            return
        }

        databaseManager.schemeVersionDao.add(
            sqlClient,
            SchemeVersion(
                pluginId = plugin.pluginId,
                key = latestMigration.to.toString(),
                extra = latestMigration.info
            )
        )
    }


    private suspend fun initTables(plugin: PanoPlugin, sqlClient: SqlClient) {
        try {
            tables[plugin]?.forEach { it.init(sqlClient) }
        } catch (e: Exception) {
            logger.error(e.toString())

            throw e
        }
    }

    private suspend fun initPluginDB(plugin: PanoPlugin, sqlClient: SqlClient) {
        initSchemeVersion(plugin, sqlClient)

        initTables(plugin, sqlClient)

        logger.info("\"${plugin.pluginId}\"'s database has been initialized")
    }

    private suspend fun updateSchemeVersion(version: Int, info: String, plugin: PanoPlugin, sqlClient: SqlClient) {
        databaseManager.schemeVersionDao.add(
            sqlClient,
            SchemeVersion(
                pluginId = plugin.pluginId,
                key = version.toString(),
                extra = info
            )
        )
    }

    private suspend fun migrate(plugin: PanoPlugin, sqlClient: SqlClient, databaseVersion: Int) {
        migrations[plugin]!!
            .find { it.isMigratable(databaseVersion) }
            ?.let {
                logger.info("Migration Found! Migrating database from version ${it.from} to ${it.to}: ${it.info}")

                try {
                    it.migrate(sqlClient)

                    updateSchemeVersion(it.to, it.info, plugin, sqlClient)
                } catch (e: Exception) {
                    logger.error("Database Error: Migration failed from version ${it.from} to ${it.to}, error: " + e)

                    main.shutdown(true)
                    return
                }

                migrate(plugin, sqlClient, it.to)
            }
    }

    suspend fun checkMigration(plugin: PanoPlugin, sqlClient: SqlClient, lastSchemeVersion: SchemeVersion?) {
        logger.info("Checking available database migrations for \"${plugin.pluginId}\"")

        val databaseVersion = lastSchemeVersion?.key?.toInt() ?: 0

        if (databaseVersion == 0) {
            logger.error("Database Error: Database scheme is not correct, please reinstall platform")

            return
        }

        migrate(plugin, sqlClient, databaseVersion)
    }

    suspend fun initialize(
        plugin: PanoPlugin
    ) {
        if (!this.tables.containsKey(plugin) || !this.migrations.containsKey(plugin)) {
            logger.error("Can't initialize database of plugin \"${plugin.pluginId}\", because it's not enabled yet!")
            return
        }

        if (!setupManager.isSetupDone()) {
            throw IllegalStateException("Can't initialize database of plugin \"${plugin.pluginId}\", because Pano setup is not finished yet!")
        }

        val sqlClient = databaseManager.getSqlClient()

        var lastSchemeVersion: SchemeVersion? = null

        try {
            lastSchemeVersion = databaseManager.schemeVersionDao.getLastSchemeVersion(plugin.pluginId, sqlClient)
        } catch (_: Exception) {
            try {
                initPluginDB(plugin, sqlClient)

                return
            } catch (e: Exception) {
                logger.error(e.message)
                logger.error("Database Error: Could not install plugin \"${plugin.pluginId}\" DB.")
            }
        }

        if (lastSchemeVersion == null) {
            initPluginDB(plugin, sqlClient)

            return
        }

        checkMigration(plugin, sqlClient, lastSchemeVersion)
    }

    suspend fun uninstall(plugin: PanoPlugin) {
        logger.info("Uninstalling database of plugin \"${plugin.pluginId}\"...")

        val sqlClient = databaseManager.getSqlClient()

        if (tables[plugin] == null) {
            logger.error("Database error: Can't uninstall database of plugin \"${plugin.pluginId}\", because it's DB is not initialized!")
            return
        }

        tables[plugin]!!.forEach { it.uninstall(sqlClient) }

        databaseManager.schemeVersionDao.deleteByPluginId(plugin.pluginId, sqlClient)

        logger.info("Successfully uninstalled database of plugin \"${plugin.pluginId}\".")
    }

    internal suspend fun checkOrphanedPlugins() {
        val sqlClient = databaseManager.getSqlClient()
        val dbPluginIds = databaseManager.schemeVersionDao.getAllPluginIds(sqlClient)
        val loadedPluginIds = pluginManager.plugins.map { it.pluginId }

        dbPluginIds.forEach { pluginId ->
            if (!loadedPluginIds.contains(pluginId)) {
                logger.warn("Plugin '$pluginId' has database records but is not loaded. It may have been incorrectly removed from the panel (Orphaned).")
            }
        }
    }
}