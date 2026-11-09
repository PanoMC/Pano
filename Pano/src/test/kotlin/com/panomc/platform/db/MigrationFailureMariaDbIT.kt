package com.panomc.platform.db

import com.panomc.platform.StartupFailure
import com.panomc.platform.db.implementation.SchemeVersionDaoImpl
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.sqlclient.SqlClient
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.slf4j.LoggerFactory

/**
 * A migration that throws stops the boot with a [StartupFailure] naming the version, instead of being logged and
 * letting Pano run on a half-migrated schema; the scheme version stays where it was so the next start retries.
 */
@EnabledIfEnvironmentVariable(named = "PANO_IT_MARIADB", matches = ".+")
class MigrationFailureMariaDbIT {
    class Failing : DatabaseMigration(1000, 1001, "Fails on purpose") {
        override val handlers: List<suspend (sqlClient: SqlClient) -> Unit> = listOf { throw IllegalStateException("boom") }
    }

    private suspend fun prepare(db: DaoMariaDb, version: String) {
        db.dropTable("scheme_version")
        db.pool.query(
            "CREATE TABLE `${DaoMariaDb.PREFIX}scheme_version` (`pluginId` varchar(255), `when` timestamp not null " +
                    "default CURRENT_TIMESTAMP, `key` varchar(255) not null, `extra` varchar(255))"
        ).execute().coAwait()
        db.pool.query("INSERT INTO `${DaoMariaDb.PREFIX}scheme_version` (`key`) VALUES ('$version')").execute().coAwait()

        db.setField(db.databaseManager, "sqlClient", db.pool)
        db.setField(db.databaseManager, "schemeVersionDao", SchemeVersionDaoImpl())
        db.setField(db.databaseManager, "logger", LoggerFactory.getLogger("MigrationFailureMariaDbIT"))
        // built by objenesis, so the lazy migration list (normally read from the Spring context) is set by hand
        db.setField(db.databaseManager, "migrations\$delegate", lazyOf(listOf<DatabaseMigration>(Failing())))
    }

    @Test
    fun `a failing migration stops the boot`() = runBlocking {
        DaoMariaDb().use { db ->
            prepare(db, "1000")

            val failure = assertThrows<StartupFailure> { runBlocking { db.databaseManager.init() } }

            assertTrue(failure.message!!.contains("1000 to 1001"), failure.message)

            val versions = db.pool.query("SELECT `key` FROM `${DaoMariaDb.PREFIX}scheme_version`").execute().coAwait()

            assertEquals(listOf("1000"), versions.map { it.getString(0) })

            db.dropTable("scheme_version")
        }
    }

    @Test
    fun `an unreadable scheme version stops the boot`() = runBlocking {
        DaoMariaDb().use { db ->
            prepare(db, "not-a-number")

            assertThrows<StartupFailure> { runBlocking { db.databaseManager.init() } }

            db.dropTable("scheme_version")
        }
    }
}
