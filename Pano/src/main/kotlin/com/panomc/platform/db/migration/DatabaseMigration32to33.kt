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
            addMissingColumns(sqlClient, "${getTablePrefix()}server", "protocolVersion" to "INT NOT NULL DEFAULT 1")
        }

    private fun addPluginVersionColumnToServerTable(): suspend (sqlClient: SqlClient) -> Unit =
        { sqlClient: SqlClient ->
            addMissingColumns(sqlClient, "${getTablePrefix()}server", "pluginVersion" to "VARCHAR(64) NULL")
        }

    private fun addCapabilitiesColumnToServerTable(): suspend (sqlClient: SqlClient) -> Unit =
        { sqlClient: SqlClient ->
            addMissingColumns(sqlClient, "${getTablePrefix()}server", "capabilities" to "TEXT NULL")
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
