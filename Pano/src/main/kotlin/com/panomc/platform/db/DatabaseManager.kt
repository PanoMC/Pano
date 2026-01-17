package com.panomc.platform.db

import com.panomc.platform.Main
import com.panomc.platform.annotation.Dao
import com.panomc.platform.annotation.Migration
import com.panomc.platform.config.ConfigManager
import com.panomc.platform.db.dao.*
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
import kotlin.system.exitProcess

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

            exitProcess(1)
        }.coAwait().close().coAwait()

        return sqlClient
    }

    internal suspend fun init() {
        checkMigration()
    }

    internal suspend fun initDatabase(sqlClient: SqlClient) {
        val databaseInitProcessHandlers = getDatabaseInitList()

        databaseInitProcessHandlers.forEach { it.init(sqlClient) }
    }

    internal fun getLatestMigration() = migrations.maxByOrNull { it.to }

    private suspend fun checkMigration() {
        logger.info("Checking available database migrations")

        val sqlClient = getSqlClient()

        val prefix = getTablePrefix()
        val databaseVersion = try {
            val rows = sqlClient.query("SELECT * FROM `${prefix}scheme_version`").execute().coAwait()
            val hasPluginId = rows.columnsNames().contains("pluginId")
            val filteredRows = if (hasPluginId) {
                rows.filter { it.getString("pluginId") == null }
            } else {
                rows
            }
            filteredRows.map { it.getString("key")?.toIntOrNull() ?: 0 }.maxByOrNull { it } ?: 0
        } catch (e: Exception) {
            logger.error("Database Error: Database scheme is not correct, please reinstall platform")

            e.printStackTrace()

            exitProcess(1)
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