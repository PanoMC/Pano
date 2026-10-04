package com.panomc.platform.db.migration

import com.panomc.platform.annotation.Migration
import com.panomc.platform.db.DatabaseMigration
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.sqlclient.SqlClient
import io.vertx.sqlclient.Tuple

@Migration
class DatabaseMigration39to40 : DatabaseMigration(
    39,
    40,
    "Add node.bootstrap and the server network columns"
) {
    override val handlers: List<suspend (SqlClient) -> Unit> = listOf(
        addBootstrapColumnToNodeTable(),
        fillBootstrapOfExistingNodes(),
        addNetworkColumnsToServerTable()
    )

    private fun addBootstrapColumnToNodeTable(): suspend (sqlClient: SqlClient) -> Unit =
        { sqlClient: SqlClient ->
            val tableName = "${getTablePrefix()}node"

            if (!columnExists(sqlClient, tableName, "bootstrap")) {
                val query = """
                    ALTER TABLE `$tableName`
                    ADD COLUMN `bootstrap` VARCHAR(16) NOT NULL DEFAULT 'MANUAL';
                """.trimIndent()

                sqlClient.query(query).execute().coAwait()
            }
        }

    // Every node that exists today was either spawned by Pano or paired by hand with a code, and
    // the kind column is what already knows which.
    private fun fillBootstrapOfExistingNodes(): suspend (sqlClient: SqlClient) -> Unit =
        { sqlClient: SqlClient ->
            val query = """
                UPDATE `${getTablePrefix()}node`
                SET `bootstrap` = 'LOCAL'
                WHERE `kind` = 'LOCAL';
            """.trimIndent()

            sqlClient.query(query).execute().coAwait()
        }

    // Written now, filled by the network templates that come next: a server belongs to at most
    // one proxy network, and its role in it decides what the node writes into its configs.
    private fun addNetworkColumnsToServerTable(): suspend (sqlClient: SqlClient) -> Unit =
        { sqlClient: SqlClient ->
            val tableName = "${getTablePrefix()}server"

            if (!columnExists(sqlClient, tableName, "networkId")) {
                sqlClient
                    .query("ALTER TABLE `$tableName` ADD COLUMN `networkId` VARCHAR(36) NULL;")
                    .execute()
                    .coAwait()
            }

            if (!columnExists(sqlClient, tableName, "networkRole")) {
                sqlClient
                    .query("ALTER TABLE `$tableName` ADD COLUMN `networkRole` VARCHAR(16) NULL;")
                    .execute()
                    .coAwait()
            }
        }

    private suspend fun columnExists(sqlClient: SqlClient, tableName: String, columnName: String): Boolean {
        val query = """
            SELECT COUNT(*) FROM `information_schema`.`COLUMNS`
            WHERE `TABLE_SCHEMA` = DATABASE()
              AND `TABLE_NAME` = ?
              AND `COLUMN_NAME` = ?
        """.trimIndent()

        val rows = sqlClient
            .preparedQuery(query)
            .execute(Tuple.of(tableName, columnName))
            .coAwait()

        return rows.toList()[0].getLong(0) > 0
    }
}
