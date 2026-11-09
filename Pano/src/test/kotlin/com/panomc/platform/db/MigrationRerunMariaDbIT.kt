package com.panomc.platform.db

import com.panomc.platform.db.migration.DatabaseMigration11to12
import com.panomc.platform.db.migration.DatabaseMigration13to14
import com.panomc.platform.db.migration.DatabaseMigration21to22
import com.panomc.platform.db.migration.DatabaseMigration34to35
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.sqlclient.Tuple
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable

/**
 * A migration that stopped half way runs again from its first handler on the next start. These older ones used
 * to fail on their own first statement then (duplicate column, missing primary key, ...) and kept Pano stuck on
 * that version; run twice they now leave the same schema as run once.
 */
@EnabledIfEnvironmentVariable(named = "PANO_IT_MARIADB", matches = ".+")
class MigrationRerunMariaDbIT {
    private val p = DaoMariaDb.PREFIX

    private suspend fun DaoMariaDb.run(vararg sql: String) = sql.forEach { pool.query(it).execute().coAwait() }

    private suspend fun DaoMariaDb.columns(table: String): List<String> = pool
        .preparedQuery(
            "SELECT `COLUMN_NAME` FROM `information_schema`.`COLUMNS` WHERE `TABLE_SCHEMA` = DATABASE() " +
                    "AND `TABLE_NAME` = ? ORDER BY `ORDINAL_POSITION`"
        )
        .execute(Tuple.of("$p$table"))
        .coAwait()
        .map { it.getString(0) }

    private suspend fun DaoMariaDb.indexes(table: String): List<String> = pool
        .preparedQuery(
            "SELECT DISTINCT `INDEX_NAME` FROM `information_schema`.`STATISTICS` WHERE `TABLE_SCHEMA` = DATABASE() " +
                    "AND `TABLE_NAME` = ? ORDER BY `INDEX_NAME`"
        )
        .execute(Tuple.of("$p$table"))
        .coAwait()
        .map { it.getString(0) }

    private suspend fun DatabaseMigration.twice(db: DaoMariaDb) {
        migrate(db.pool)
        migrate(db.pool)
    }

    @Test
    fun `13 to 14 adds one unique index on email`() = runBlocking {
        DaoMariaDb().use { db ->
            db.dropTable("user")
            db.run("CREATE TABLE `${p}user` (`id` bigint NOT NULL AUTO_INCREMENT, `email` varchar(255) NOT NULL, PRIMARY KEY (`id`))")

            DatabaseMigration13to14().twice(db)

            assertEquals(setOf("PRIMARY", "email"), db.indexes("user").toSet())

            db.dropTable("user")
        }
    }

    @Test
    fun `21 to 22 can run again after the primary key is gone`() = runBlocking {
        DaoMariaDb().use { db ->
            db.dropTable("scheme_version")
            db.run(
                "CREATE TABLE `${p}scheme_version` (`when` timestamp not null default CURRENT_TIMESTAMP, " +
                        "`key` varchar(255) not null, `extra` varchar(255), PRIMARY KEY (`key`))"
            )

            DatabaseMigration21to22().twice(db)

            assertEquals(listOf("when", "pluginId", "key", "extra"), db.columns("scheme_version"))
            assertEquals(setOf("pluginId_key_idx"), db.indexes("scheme_version").toSet())

            db.dropTable("scheme_version")
        }
    }

    @Test
    fun `11 to 12 renames once`() = runBlocking {
        DaoMariaDb().use { db ->
            listOf("notification", "panel_notification").forEach { table ->
                db.dropTable(table)
                db.run("CREATE TABLE `$p$table` (`id` bigint NOT NULL AUTO_INCREMENT, `properties` text, `date` bigint NOT NULL, PRIMARY KEY (`id`))")
            }

            DatabaseMigration11to12().twice(db)

            listOf("notification", "panel_notification").forEach { table ->
                assertEquals(listOf("id", "details", "createdAt", "updatedAt"), db.columns(table))
                db.dropTable(table)
            }
        }
    }

    @Test
    fun `34 to 35 adds the managed server columns and indexes once`() = runBlocking {
        DaoMariaDb().use { db ->
            listOf("server", "node", "server_task").forEach { db.dropTable(it) }
            db.run(
                "CREATE TABLE `${p}server` (`id` bigint NOT NULL AUTO_INCREMENT, `name` varchar(255), PRIMARY KEY (`id`))",
                "INSERT INTO `${p}server` (`name`) VALUES ('a'), ('b')"
            )

            DatabaseMigration34to35().twice(db)

            assertEquals(
                listOf(
                    "id", "name", "kind", "nodeId", "uuid", "software", "softwareVersion", "javaVersion", "memoryMb",
                    "jvmArgs", "gamePort", "autoStart", "crashRestart", "lastExitCode", "processState"
                ),
                db.columns("server")
            )
            assertEquals(setOf("PRIMARY", "idx_server_node", "idx_server_uuid"), db.indexes("server").toSet())

            listOf("server", "node", "server_task").forEach { db.dropTable(it) }
        }
    }
}
