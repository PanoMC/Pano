package com.panomc.platform.db

import com.panomc.platform.AppConstants
import com.panomc.platform.db.implementation.LocaleDaoImpl
import com.panomc.platform.db.migration.DatabaseMigration66to67
import io.vertx.kotlin.coroutines.coAwait
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable

/** One row per locale code: a duplicate made the language count as missing. */
@EnabledIfEnvironmentVariable(named = "PANO_IT_MARIADB", matches = ".+")
class LocaleCodeUniqueMariaDbIT {
    private val p = DaoMariaDb.PREFIX

    private suspend fun DaoMariaDb.run(vararg sql: String) = sql.forEach { pool.query(it).execute().coAwait() }

    private suspend fun DaoMariaDb.ids(table: String, column: String = "id"): List<String> =
        pool.query("SELECT `$column` FROM `$p$table` ORDER BY `id`").execute().coAwait().map { it.getValue(0).toString() }

    @Test
    fun `a fresh install gets each default locale once and a unique code`() = runBlocking {
        DaoMariaDb().use { db ->
            val dao = LocaleDaoImpl()

            db.dropTable("locale")
            dao.init(db.pool)
            dao.init(db.pool)

            assertEquals(AppConstants.DEFAULT_LOCALES.size, db.ids("locale").size)
            assertTrue(dao.existsByCode(AppConstants.DEFAULT_LOCALES.first().code, db.pool))

            assertThrows<Exception> {
                runBlocking { dao.add(AppConstants.DEFAULT_LOCALES.first().copy(code = AppConstants.DEFAULT_LOCALES.first().code.uppercase()), db.pool) }
            }

            db.dropTable("locale")
        }
    }

    @Test
    fun `66 to 67 keeps the oldest row of each code and its translations`() = runBlocking {
        DaoMariaDb().use { db ->
            db.dropTable("locale")
            db.dropTable("translation")
            db.run(
                "CREATE TABLE `${p}locale` (`id` bigint NOT NULL AUTO_INCREMENT, `code` varchar(10) NOT NULL, PRIMARY KEY (`id`)) DEFAULT CHARSET=utf8mb4",
                "CREATE TABLE `${p}translation` (`id` bigint NOT NULL AUTO_INCREMENT, `localeId` varchar(10) NOT NULL, " +
                        "`type` varchar(255) NOT NULL, `key` text NOT NULL, `value` text NOT NULL, PRIMARY KEY (`id`)) DEFAULT CHARSET=utf8mb4",
                "INSERT INTO `${p}locale` (`id`, `code`) VALUES (1, 'en-US'), (2, 'tr'), (3, 'EN-us'), (4, 'en-US')",
                "INSERT INTO `${p}translation` (`localeId`, `type`, `key`, `value`) VALUES " +
                        "('1', 'T', 'a', 'kept'), ('3', 'T', 'a', 'dropped'), ('3', 'T', 'b', 'moved'), ('2', 'T', 'a', 'tr')"
            )

            val migration = DatabaseMigration66to67()

            migration.migrate(db.pool)
            migration.migrate(db.pool)

            assertEquals(listOf("1", "2"), db.ids("locale"))
            assertEquals(
                listOf("kept", "moved", "tr"),
                db.pool.query("SELECT `value` FROM `${p}translation` ORDER BY `value`").execute().coAwait().map { it.getString(0) }
            )
            assertTrue(LocaleDaoImpl().existsByCode("en-us", db.pool))
            assertThrows<Exception> { runBlocking { db.run("INSERT INTO `${p}locale` (`code`) VALUES ('EN-US')") } }

            db.dropTable("locale")
            db.dropTable("translation")
        }
    }
}
