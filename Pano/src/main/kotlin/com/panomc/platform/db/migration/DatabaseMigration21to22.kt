package com.panomc.platform.db.migration

import com.panomc.platform.annotation.Migration
import com.panomc.platform.db.DatabaseMigration
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.sqlclient.SqlClient

@Migration
class DatabaseMigration21to22 : DatabaseMigration(21, 22, "Update scheme_version table to support multiple plugins with same version key using UNIQUE index.") {
    override val handlers: List<suspend (SqlClient) -> Unit> = listOf(
        updateSchemeVersionTable()
    )

    private fun updateSchemeVersionTable(): suspend (sqlClient: SqlClient) -> Unit =
        { sqlClient: SqlClient ->
            val tableName = "${getTablePrefix()}scheme_version"

            // 1. Add the pluginId column
            addMissingColumns(sqlClient, tableName, "pluginId" to "VARCHAR(255) AFTER `when`")

            // 2. Drop the old PRIMARY KEY (`key`): the unique index below has to allow a null pluginId, which a primary
            // key would not.
            if (hasIndex(sqlClient, tableName, "PRIMARY")) {
                sqlClient.query("ALTER TABLE `$tableName` DROP PRIMARY KEY").execute().coAwait()
            }

            // 3. Add UNIQUE index on (`pluginId`, `key`)
            addIndexIfMissing(
                sqlClient,
                tableName,
                "pluginId_key_idx",
                "ALTER TABLE `$tableName` ADD UNIQUE INDEX `pluginId_key_idx` (`pluginId`, `key`)"
            )
        }
}
