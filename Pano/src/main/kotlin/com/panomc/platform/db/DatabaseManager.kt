package com.panomc.platform.db

import com.panomc.platform.StartupFailure
import com.panomc.platform.Main
import com.panomc.platform.annotation.Dao
import com.panomc.platform.annotation.Migration
import com.panomc.platform.config.ConfigManager
import com.panomc.platform.db.dao.*
import com.panomc.platform.error.PlatformAlreadyInstalled
import com.panomc.platform.hosted.HostedEnvConfig
import io.vertx.core.Vertx
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.mysqlclient.MySQLBuilder
import io.vertx.mysqlclient.MySQLConnectOptions
import io.vertx.sqlclient.Pool
import io.vertx.sqlclient.PoolOptions
import io.vertx.sqlclient.SqlClient
import org.slf4j.Logger
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.context.annotation.Lazy
import org.springframework.stereotype.Component

@Lazy
@Component
class DatabaseManager(
    @Lazy val schemeVersionDao: SchemeVersionDao,
    @Lazy val userDao: UserDao,
    @Lazy val panelConfigDao: PanelConfigDao,
    @Lazy val serverDao: ServerDao,
    @Lazy val systemPropertyDao: SystemPropertyDao,
    @Lazy val panelNotificationDao: PanelNotificationDao,
    @Lazy val postDao: PostDao,
    @Lazy val postAutosaveDao: PostAutosaveDao,
    @Lazy val postViewTrackerDao: PostViewTrackerDao,
    @Lazy val postCategoryDao: PostCategoryDao,
    @Lazy val ticketDao: TicketDao,
    @Lazy val ticketCategoryDao: TicketCategoryDao,
    @Lazy val ticketMessageDao: TicketMessageDao,
    @Lazy val ticketTemplateDao: TicketTemplateDao,
    @Lazy val permissionGroupDao: PermissionGroupDao,
    @Lazy val permissionTrackDao: PermissionTrackDao,
    @Lazy val permissionNodeDao: PermissionNodeDao,
    @Lazy val websiteViewDao: WebsiteViewDao,
    @Lazy val tokenDao: TokenDao,
    @Lazy val notificationDao: NotificationDao,
    @Lazy val serverPlayerDao: ServerPlayerDao,
    @Lazy val serverMetricDao: ServerMetricDao,
    @Lazy val serverMetricDailyDao: ServerMetricDailyDao,
    @Lazy val nodeDao: NodeDao,
    @Lazy val serverTaskDao: ServerTaskDao,
    @Lazy val serverBackupDao: ServerBackupDao,
    @Lazy val serverScheduleDao: ServerScheduleDao,
    @Lazy val serverScheduleTaskDao: ServerScheduleTaskDao,
    @Lazy val serverAlertDao: ServerAlertDao,
    @Lazy val serverPluginInstallDao: ServerPluginInstallDao,
    @Lazy val nodePendingDeletionDao: NodePendingDeletionDao,
    @Lazy val resourceHashDao: ResourceHashDao,
    @Lazy val panelActivityLogDao: PanelActivityLogDao,
    @Lazy val localeDao: LocaleDao,
    @Lazy val translationDao: TranslationDao,
    @Lazy val banHistoryDao: BanHistoryDao,
    @Lazy val bannedIpDao: BannedIpDao,
    @Lazy val onlinePlayerHistoryDao: OnlinePlayerHistoryDao,
    @Lazy val pendingAuthSessionDao: PendingAuthSessionDao,
    @Lazy val mailTemplateDao: MailTemplateDao,
    @Lazy val mailLayoutDao: MailLayoutDao,
    @Lazy val mailSettingDao: MailSettingDao,
) {

    @Autowired
    private lateinit var vertx: Vertx

    @Autowired
    private lateinit var logger: Logger

    @Autowired
    private lateinit var configManager: ConfigManager

    @Autowired
    private lateinit var applicationContext: AnnotationConfigApplicationContext

    @Autowired
    private lateinit var mariaDBManager: MariaDBManager

    @Autowired
    private lateinit var main: Main

    private lateinit var sqlClient: SqlClient

    private val migrations by lazy {
        val beans = applicationContext.getBeansWithAnnotation(Migration::class.java)

        beans.filter { it.value is DatabaseMigration }
            .map { it.value as DatabaseMigration }
            .sortedBy { it.from }
    }

    fun getTablePrefix(): String = configManager.config.database.prefix

    suspend fun getSqlClient(): SqlClient {
        if (::sqlClient.isInitialized) {
            return sqlClient
        }

        val databaseConfig = configManager.config.database

        // Before the set-up's database step there is nothing to connect to. Building a pool here would keep a
        // pool without a host for the whole process, and the end of the set-up would wait on it forever.
        if (databaseConfig.host.isBlank()) {
            throw StartupFailure("The database is not configured yet.")
        }

        var port = 3306
        var host = databaseConfig.host

        if (host.contains(":")) {
            val splitHost = host.split(":")

            host = splitHost[0]

            port = splitHost[1].toInt()
        }

        val connectOptions = MySQLConnectOptions()
            .setPort(port)
            .setHost(host)
            .setDatabase(databaseConfig.name)
            .setUser(databaseConfig.username)

        if (databaseConfig.password != "")
            connectOptions.password = databaseConfig.password

        val poolOptions = PoolOptions()
            .setMaxSize(HostedEnvConfig.current.dbPoolSize)

        val pooledClient = MySQLBuilder.pool()
            .with(poolOptions)
            .connectingTo(connectOptions)
            .using(vertx)
            .build()

        try {
            pooledClient.connection.coAwait().close().coAwait()
        } catch (e: Exception) {
            // Not kept: the next call builds a pool from the settings of that moment.
            pooledClient.close()

            throw StartupFailure("Failed to connect database! Please check your configuration!", e)
        }

        sqlClient = pooledClient

        return sqlClient
    }

    internal suspend fun init() {
        checkMigration()
    }

    internal suspend fun initDatabase(sqlClient: SqlClient) {
        try {
            val lastSchemeVersion = schemeVersionDao.getLastSchemeVersion(sqlClient)

            if (lastSchemeVersion != null) {
                throw PlatformAlreadyInstalled()
            }
        } catch (_: Exception) {
            // PlatformAlreadyInstalled is a Throwable, not an Exception, so it is never caught here
        }

        val databaseInitProcessHandlers = getDatabaseInitList()

        databaseInitProcessHandlers.forEach { it.init(sqlClient) }
    }

    internal fun getLatestMigration() = migrations.maxByOrNull { it.to }

    private suspend fun checkMigration() {
        logger.info("Checking available database migrations")

        val sqlClient = getSqlClient()

        val databaseVersion = try {
            schemeVersionDao.getLastSchemeVersion(sqlClient)?.key?.toIntOrNull() ?: 0
        } catch (e: Exception) {
            logger.error("Database Error: Database scheme is not correct, please reinstall platform")

            e.printStackTrace()

            main.shutdown(true)
            return
        }

        if (databaseVersion == 0) {
            logger.error("Database Error: Database scheme is not correct, please reinstall platform")

            return
        }

        migrate(sqlClient, databaseVersion)
    }

    private suspend fun migrate(sqlClient: SqlClient, databaseVersion: Int) {
        migrations
            .find { it.isMigratable(databaseVersion) }
            ?.let {
                logger.info("Migration Found! Migrating database from version ${it.from} to ${it.to}: ${it.info}")

                try {
                    it.migrate(sqlClient)

                    it.updateSchemeVersion(sqlClient)
                } catch (e: Exception) {
                    logger.error("Database Error: Migration failed from version ${it.from} to ${it.to}, error: " + e)

                    return
                }

                migrate(sqlClient, it.to)
            }
    }

    private fun getDatabaseInitList(): List<com.panomc.platform.db.Dao<*>> {
        val beans = applicationContext.getBeansWithAnnotation(Dao::class.java)

        return beans.map { it.value as com.panomc.platform.db.Dao<*> }
    }
}