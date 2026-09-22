package com.panomc.platform.db.migration

import com.panomc.platform.annotation.Migration
import com.panomc.platform.db.DatabaseMigration
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.sqlclient.SqlClient

@Migration
class DatabaseMigration32to33 : DatabaseMigration(
    32,
    33,
    "Add protocolVersion, pluginVersion, capabilities to server table"
) {
    override val handlers: List<suspend (SqlClient) -> Unit> = listOf(
        addProtocolVersionColumnToServerTable(),
        addPluginVersionColumnToServerTable(),
        addCapabilitiesColumnToServerTable(),
        fillCapabilitiesOfExistingServers()
    )

    private fun addProtocolVersionColumnToServerTable(): suspend (sqlClient: SqlClient) -> Unit =
        { sqlClient: SqlClient ->
            val addColumnQuery = """
            ALTER TABLE `${getTablePrefix()}server`
            ADD COLUMN `protocolVersion` INT NOT NULL DEFAULT 1;
        """.trimIndent()

            sqlClient.preparedQuery(addColumnQuery).execute().coAwait()
        }

    private fun addPluginVersionColumnToServerTable(): suspend (sqlClient: SqlClient) -> Unit =
        { sqlClient: SqlClient ->
            val addColumnQuery = """
            ALTER TABLE `${getTablePrefix()}server`
            ADD COLUMN `pluginVersion` VARCHAR(64) NULL;
        """.trimIndent()

            sqlClient.preparedQuery(addColumnQuery).execute().coAwait()
        }

    private fun addCapabilitiesColumnToServerTable(): suspend (sqlClient: SqlClient) -> Unit =
        { sqlClient: SqlClient ->
            val addColumnQuery = """
            ALTER TABLE `${getTablePrefix()}server`
            ADD COLUMN `capabilities` TEXT NULL;
        """.trimIndent()

            sqlClient.preparedQuery(addColumnQuery).execute().coAwait()
        }

    private fun fillCapabilitiesOfExistingServers(): suspend (sqlClient: SqlClient) -> Unit =
        { sqlClient: SqlClient ->
            val query = """
            UPDATE `${getTablePrefix()}server`
            SET `capabilities` = '[]'
            WHERE `capabilities` IS NULL;
        """.trimIndent()

            sqlClient.preparedQuery(query).execute().coAwait()
        }
}
