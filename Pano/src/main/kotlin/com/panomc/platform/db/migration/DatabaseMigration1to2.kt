package com.panomc.platform.db.migration

import com.panomc.platform.annotation.Migration
import com.panomc.platform.db.DatabaseMigration
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.sqlclient.SqlClient

@Migration
class DatabaseMigration1to2 : DatabaseMigration(1, 2, "Add addon_hash table") {
    override val handlers: List<suspend (SqlClient) -> Unit> = listOf(
        createAddonHashTable()
    )

    private fun createAddonHashTable(): suspend (sqlClient: SqlClient) -> Unit =
        { sqlClient: SqlClient ->
            sqlClient
                .query(
                    """
                            CREATE TABLE IF NOT EXISTS `${getTablePrefix()}addon_hash` (
                              `id` bigint NOT NULL AUTO_INCREMENT,
                              `hash` text NOT NULL,
                              `status` varchar(255) NOT NULL,
                              PRIMARY KEY (`id`)
                            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Addon hash table.';
                """.trimIndent()
                )
                .execute()
                .coAwait()
        }

}