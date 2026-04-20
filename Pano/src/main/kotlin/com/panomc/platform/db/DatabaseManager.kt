package com.panomc.platform.db

import com.panomc.platform.Main
import com.panomc.platform.annotation.Dao
import com.panomc.platform.annotation.Migration
import com.panomc.platform.config.ConfigManager
import com.panomc.platform.db.dao.*
import com.panomc.platform.error.PlatformAlreadyInstalled
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
    @Lazy val postViewTrackerDao: PostViewTrackerDao,
    @Lazy val postCategoryDao: PostCategoryDao,
    @Lazy val ticketDao: TicketDao,
    @Lazy val ticketCategoryDao: TicketCategoryDao,
    @Lazy val ticketMessageDao: TicketMessageDao,
    @Lazy val permissionGroupDao: PermissionGroupDao,
    @Lazy val permissionTrackDao: PermissionTrackDao,
    @Lazy val permissionNodeDao: PermissionNodeDao,
    @Lazy val websiteViewDao: WebsiteViewDao,
    @Lazy val tokenDao: TokenDao,
    @Lazy val notificationDao: NotificationDao,
    @Lazy val serverPlayerDao: ServerPlayerDao,
    @Lazy val resourceHashDao: ResourceHashDao,
    @Lazy val panelActivityLogDao: PanelActivityLogDao,
    @Lazy val localeDao: LocaleDao,
    @Lazy val translationDao: TranslationDao,
    @Lazy val banHistoryDao: BanHistoryDao,
    @Lazy val onlinePlayerHistoryDao: OnlinePlayerHistoryDao,
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
            .setMaxSize(100)

        sqlClient = MySQLBuilder.pool()
            .with(poolOptions)
            .connectingTo(connectOptions)
            .using(vertx)
            .build()

        val pooledClient = sqlClient as Pool

        pooledClient.connection.onFailure {
            logger.error("Failed to connect database! Please check your configuration!")

            it.printStackTrace()

            System.exit(1)
        }.coAwait().close().coAwait()

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
        } catch (e: Exception) {
            if (e is PlatformAlreadyInstalled) {
                throw e
            }
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