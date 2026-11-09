package com.panomc.platform.db

import com.panomc.platform.Main
import com.panomc.platform.config.ConfigManager
import com.panomc.platform.config.PanoConfig
import com.panomc.platform.util.deserializer.BooleanDeserializer
import com.google.gson.GsonBuilder
import io.vertx.core.Vertx
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.mysqlclient.MySQLBuilder
import io.vertx.mysqlclient.MySQLConnectOptions
import io.vertx.sqlclient.Pool
import io.vertx.sqlclient.PoolOptions
import org.springframework.objenesis.ObjenesisStd
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.AnnotationConfigApplicationContext

/**
 * A real MariaDB for DAO tests: `PANO_IT_MARIADB=host:port`, `PANO_IT_MARIADB_PASSWORD` (root) and
 * `PANO_IT_MARIADB_DATABASE` (a scratch database the test may fill; tables use the prefix [PREFIX]).
 * Creating one points `Main.applicationContext` at a context whose DatabaseManager answers that prefix; [close] puts
 * the previous context back.
 */
class DaoMariaDb(private val prefix: String = PREFIX) : AutoCloseable {
    val vertx: Vertx = Vertx.vertx()

    private val address = System.getenv("PANO_IT_MARIADB") ?: "127.0.0.1:3306"
    private val password = System.getenv("PANO_IT_MARIADB_PASSWORD") ?: "pano"
    val database: String = System.getenv("PANO_IT_MARIADB_DATABASE") ?: "pano_it_dao"

    val pool: Pool = MySQLBuilder.pool()
        .connectingTo(
            MySQLConnectOptions().setHost(address.substringBefore(':')).setPort(address.substringAfter(':').toInt())
                .setUser("root").setPassword(password).setDatabase(database)
        )
        .with(PoolOptions().setMaxSize(2))
        .using(vertx)
        .build()

    private val previousContext = runCatching { Main.applicationContext }.getOrNull()

    init {
        if (runCatching { DBEntity.gson }.isFailure) {
            DBEntity.gson = GsonBuilder()
                .registerTypeAdapter(Boolean::class.java, BooleanDeserializer())
                .registerTypeAdapter(java.lang.Boolean::class.java, BooleanDeserializer())
                .create()
        }

        val objenesis = ObjenesisStd()
        val databaseManager = objenesis.newInstance(DatabaseManager::class.java)
        val configManager = ConfigManager(vertx, LoggerFactory.getLogger("DaoMariaDb"), AnnotationConfigApplicationContext())
        val config = objenesis.newInstance(PanoConfig::class.java)

        setField(config, "database", PanoConfig.Companion.DatabaseConfig(prefix = prefix))
        setField(configManager, "config", config)
        setField(databaseManager, "configManager", configManager)

        Main.applicationContext = AnnotationConfigApplicationContext().apply {
            refresh()
            beanFactory.registerSingleton("databaseManager", databaseManager)
        }
    }

    suspend fun dropTable(name: String) {
        pool.query("DROP TABLE IF EXISTS `$prefix$name`").execute().coAwait()
    }

    override fun close() {
        pool.close()
        vertx.close()
        previousContext?.let { Main.applicationContext = it }
    }

    private fun setField(target: Any, name: String, value: Any) {
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(target, value)
    }

    companion object {
        const val PREFIX = "daoit_"
    }
}
