package com.panomc.platform.db

import com.panomc.platform.annotation.Dao
import com.panomc.platform.annotation.Migration
import com.panomc.platform.config.ConfigManager
import com.panomc.platform.db.dao.*
import com.panomc.platform.db.model.SchemeVersion
import io.vertx.core.Vertx
import io.vertx.mysqlclient.MySQLConnectOptions
import io.vertx.mysqlclient.MySQLPool
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
    @Lazy val permissionDao: PermissionDao,
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
    @Lazy val permissionGroupPermsDao: PermissionGroupPermsDao,
    @Lazy val websiteViewDao: WebsiteViewDao,
    @Lazy val tokenDao: TokenDao,
    @Lazy val notificationDao: NotificationDao,
    @Lazy val serverPlayerDao: ServerPlayerDao
) {

    @Autowired
    private lateinit var vertx: Vertx

    @Autowired
    private lateinit var logger: Logger

    @Autowired
    private lateinit var configManager: ConfigManager

    @Autowired
    private lateinit var applicationContext: AnnotationConfigApplicationContext

    private lateinit var sqlClient: SqlClient

    private val migrations by lazy {
        val beans = applicationContext.getBeansWithAnnotation(Migration::class.java)

        beans.filter { it.value is DatabaseMigration }
            .map { it.value as DatabaseMigration }
            .sortedBy { it.FROM_SCHEME_VERSION }
    }

    fun getTablePrefix(): String = configManager.getConfig().getJsonObject("database").getString("prefix")

    fun getSqlClient(): SqlClient {
        if (!::sqlClient.isInitialized) {
            val databaseConfig = configManager.getConfig().getJsonObject("database")

            var port = 3306
            var host = databaseConfig.getString("host")

            if (host.contains(":")) {
                val splitHost = host.split(":")

                host = splitHost[0]

                port = splitHost[1].toInt()
            }

            val connectOptions = MySQLConnectOptions()
                .setPort(port)
                .setHost(host)
                .setDatabase(databaseConfig.getString("name"))
                .setUser(databaseConfig.getString("username"))

            if (databaseConfig.getString("password") != "")
                connectOptions.password = databaseConfig.getString("password")

            val poolOptions = PoolOptions()
                .setMaxSize(100)

            sqlClient = MySQLPool.client(vertx, connectOptions, poolOptions)
        }

        try {
            return sqlClient
        } catch (e: Exception) {
            logger.error("Failed to connect database! Please check your configuration! Error is: $e")

            throw e
        }
    }

    internal suspend fun init() {
        checkMigration()
    }

    internal suspend fun initDatabase(sqlClient: SqlClient) {
        val databaseInitProcessHandlers = getDatabaseInitList()

        databaseInitProcessHandlers.forEach { it.init(sqlClient) }
    }

    internal fun getLatestMigration() = migrations.maxByOrNull { it.SCHEME_VERSION }!!

    private suspend fun checkMigration() {
        logger.info("Checking available database migrations")

        val sqlClient: SqlClient

        try {
            sqlClient = getSqlClient()
        } catch (e: Exception) {
            logger.info("Connection to database failed! Database migration is skipped.")

            return
        }
        val lastSchemeVersion: SchemeVersion?

        try {
            lastSchemeVersion = schemeVersionDao.getLastSchemeVersion(sqlClient)
        } catch (e: Exception) {
            logger.error("Database Error: Database scheme is not correct, please reinstall platform")

            return
        }

        val databaseVersion = lastSchemeVersion?.key?.toIntOrNull() ?: 0

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
                logger.info("Migration Found! Migrating database from version ${it.FROM_SCHEME_VERSION} to ${it.SCHEME_VERSION}: ${it.SCHEME_VERSION_INFO}")

                try {
                    it.migrate(sqlClient)

                    it.updateSchemeVersion(sqlClient)
                } catch (e: Exception) {
                    logger.error("Database Error: Migration failed from version ${it.FROM_SCHEME_VERSION} to ${it.SCHEME_VERSION}, error: " + e)

                    return
                }

                migrate(sqlClient, it.SCHEME_VERSION)
            }
    }

    private fun getDatabaseInitList(): List<com.panomc.platform.db.Dao<*>> {
        val beans = applicationContext.getBeansWithAnnotation(Dao::class.java)

        return beans.map { it.value as com.panomc.platform.db.Dao<*> }
    }
}