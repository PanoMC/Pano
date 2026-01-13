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
            val prefix = getTablePrefix()
            val tableName = "${prefix}scheme_version"

            // 1. Add pluginId column if it doesn't exist (MySQL 8.0.19+ supports IF NOT EXISTS, but for safety we can use a generic approach or just ADD if we sure it's missing)
            val columns = sqlClient.query("SHOW COLUMNS FROM `$tableName` LIKE 'pluginId'").execute().coAwait()
            if (columns.size() == 0) {
                sqlClient.query("ALTER TABLE `$tableName` ADD COLUMN `pluginId` VARCHAR(255) AFTER `when`").execute().coAwait()
            }

            // 2. Drop the old PRIMARY KEY (`key`)
            // We need to drop the primary key before adding a unique index that includes it, 
            // especially since we want to allow nulls in pluginId which PRIMARY KEY wouldn't allow.
            sqlClient.query("ALTER TABLE `$tableName` DROP PRIMARY KEY").execute().coAwait()

            // 3. Add UNIQUE index on (`pluginId`, `key`)
            sqlClient.query("ALTER TABLE `$tableName` ADD UNIQUE INDEX `pluginId_key_idx` (`pluginId`, `key`)").execute().coAwait()
        }
}
