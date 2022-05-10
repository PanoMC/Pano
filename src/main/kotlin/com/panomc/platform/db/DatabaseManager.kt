package com.panomc.platform.db

import com.panomc.platform.annotation.Dao
import com.panomc.platform.annotation.Migration
import com.panomc.platform.config.ConfigManager
import com.panomc.platform.db.dao.*
import io.vertx.core.AsyncResult
import io.vertx.core.Future
import io.vertx.core.Vertx
import io.vertx.mysqlclient.MySQLConnectOptions
import io.vertx.mysqlclient.MySQLPool
import io.vertx.sqlclient.Pool
import io.vertx.sqlclient.PoolOptions
import io.vertx.sqlclient.SqlConnection
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
    @Lazy val permissionGroupPermsDao: PermissionGroupPermsDao
) {

    @Autowired
    private lateinit var vertx: Vertx

    @Autowired
    private lateinit var logger: Logger

    @Autowired
    private lateinit var configManager: ConfigManager

    @Autowired
    private lateinit var applicationContext: AnnotationConfigApplicationContext

    private lateinit var pool: Pool

    private val mMigrations by lazy {
        val beans = applicationContext.getBeansWithAnnotation(Migration::class.java)

        beans.filter { it.value is DatabaseMigration }.map { it.value as DatabaseMigration }
            .sortedBy { it.FROM_SCHEME_VERSION }
    }

    fun getTablePrefix() = configManager.getConfig().getJsonObject("database").getString("prefix")

    fun createConnection(handler: (sqlConnection: SqlConnection?, asyncResult: AsyncResult<SqlConnection>) -> Unit) {
        if (!::pool.isInitialized) {
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
                .setMaxSize(10)

            pool = MySQLPool.pool(vertx, connectOptions, poolOptions)
        }

        pool.getConnection { getConnection ->
            if (getConnection.succeeded())
                handler.invoke(getConnection.result(), getConnection)
            else {
                logger.error("Failed to connect database! Please check your configuration! Error is: ${getConnection.cause()}")

                handler.invoke(null, getConnection)
            }
        }
    }

    fun closeConnection(sqlConnection: SqlConnection, handler: ((asyncResult: AsyncResult<Void?>?) -> Unit)? = null) {
        sqlConnection.close {
            handler?.invoke(it)
        }
    }

    internal fun init(): Future<Any> = Future.future { future ->
        checkMigration().onComplete {
            future.complete(it)
        }
    }

    internal fun initDatabase(sqlConnection: SqlConnection, handler: (asyncResult: AsyncResult<*>) -> Unit = {}) {
        val databaseInitProcessHandlers = getDatabaseInitList()

        var currentIndex = 0

        fun invoke() {
            val localHandler: (AsyncResult<*>) -> Unit = {
                when {
                    it.failed() || currentIndex == databaseInitProcessHandlers.lastIndex -> closeConnection(
                        sqlConnection
                    ) { _ ->
                        handler.invoke(it)
                    }
                    else -> {
                        currentIndex++

                        invoke()
                    }
                }
            }

            if (currentIndex <= databaseInitProcessHandlers.lastIndex)
                databaseInitProcessHandlers[currentIndex].invoke(sqlConnection, localHandler)
        }

        invoke()
    }

    internal fun getLatestMigration() = mMigrations.maxByOrNull { it.SCHEME_VERSION }!!

    private fun checkMigration(): Future<Any> = Future.future { future ->
        logger.info("Checking available database migrations...")

        createConnection { sqlConnection, _ ->
            if (sqlConnection == null) {
                logger.error("Connection to database failed! Database migration is skipped.")

                future.complete()

                return@createConnection
            }

            schemeVersionDao.getLastSchemeVersion(sqlConnection) { schemeVersion, _ ->
                if (schemeVersion == null)
                    logger.error("Database Error: Database scheme is not correct, please reinstall platform")
                else {
                    val databaseVersion = schemeVersion.key.toIntOrNull() ?: 0

                    if (databaseVersion == 0)
                        logger.error("Database Error: Database scheme is not correct, please reinstall platform")
                    else
                        migrate(sqlConnection, databaseVersion).onComplete {
                            future.complete(it)
                        }
                }
            }
        }
    }

    private fun migrate(sqlConnection: SqlConnection, databaseVersion: Int): Future<Any> = Future.future { future ->
        val handlers = mMigrations.map { it.migrate(sqlConnection) }

        var currentIndex = 0

        fun invoke() {
            val localHandler: (AsyncResult<*>) -> Unit = { localHandlerResult ->
                fun check() {
                    when {
                        localHandlerResult.failed() -> closeConnection(sqlConnection) {
                            logger.error("Database Error: Migration failed from version ${mMigrations[currentIndex].FROM_SCHEME_VERSION} to ${mMigrations[currentIndex].SCHEME_VERSION}, error: " + localHandlerResult.cause())
                        }
                        currentIndex == handlers.lastIndex -> closeConnection(sqlConnection)
                        else -> {
                            currentIndex++

                            invoke()
                        }
                    }
                }

                if (localHandlerResult.succeeded())
                    mMigrations[currentIndex].updateSchemeVersion(sqlConnection)
                        .invoke { updateSchemeVersion ->
                            if (updateSchemeVersion.failed())
                                closeConnection(sqlConnection) {
                                    logger.error("Database Error: Migration failed from version ${mMigrations[currentIndex].FROM_SCHEME_VERSION} to ${mMigrations[currentIndex].SCHEME_VERSION}, error: " + updateSchemeVersion.cause())
                                }
                            else
                                check()
                        }
                else
                    check()
            }

            if (mMigrations[currentIndex].isMigratable(databaseVersion)) {
                if (currentIndex <= handlers.lastIndex) {
                    logger.info("Migration Found! Migrating database from version ${mMigrations[currentIndex].FROM_SCHEME_VERSION} to ${mMigrations[currentIndex].SCHEME_VERSION}: ${mMigrations[currentIndex].SCHEME_VERSION_INFO}")

                    handlers[currentIndex].invoke(localHandler)
                }
            } else if (currentIndex == handlers.lastIndex) {
                closeConnection(sqlConnection) {
                    future.complete()
                }
            } else {
                currentIndex++

                invoke()
            }
        }

        invoke()
    }

    private fun getDatabaseInitList(): List<(SqlConnection, (AsyncResult<*>) -> Unit) -> Unit> {
        val beans = applicationContext.getBeansWithAnnotation(Dao::class.java)

        val daoList = beans.map { it.value as com.panomc.platform.db.Dao<*> }

        return daoList.map { it.init() }
    }
}