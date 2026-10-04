package com.panomc.platform.db.migration

import com.panomc.platform.annotation.Migration
import com.panomc.platform.db.DatabaseMigration
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.sqlclient.SqlClient

@Migration
class DatabaseMigration35to36 : DatabaseMigration(
    35,
    36,
    "Add server.properties settings to the server table"
) {
    override val handlers: List<suspend (SqlClient) -> Unit> = listOf(
        addPropertiesColumnToServerTable(),
        fillPropertiesOfExistingServers()
    )

    private fun addPropertiesColumnToServerTable(): suspend (sqlClient: SqlClient) -> Unit =
        { sqlClient: SqlClient ->
            val query = """
            ALTER TABLE `${getTablePrefix()}server`
            ADD COLUMN `properties` TEXT NULL;
        """.trimIndent()

            sqlClient.preparedQuery(query).execute().coAwait()
        }

    // Backfilled like jvmArgs was, and read leniently on top of that: a row written by an older
    // Pano, or by a plugin doing its own INSERT, still has NULL here and must not turn into a null
    // inside a non-null Kotlin map.
    private fun fillPropertiesOfExistingServers(): suspend (sqlClient: SqlClient) -> Unit =
        { sqlClient: SqlClient ->
            val query = """
            UPDATE `${getTablePrefix()}server`
            SET `properties` = '{}'
            WHERE `properties` IS NULL;
        """.trimIndent()

            sqlClient.preparedQuery(query).execute().coAwait()
        }
}
