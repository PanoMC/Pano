package com.panomc.platform.db.migration

import com.panomc.platform.annotation.Migration
import com.panomc.platform.db.DatabaseMigration
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.sqlclient.SqlClient
import io.vertx.sqlclient.Tuple

@Migration
class DatabaseMigration29to30 : DatabaseMigration(
    29,
    30,
    "Add source column to ban_history table and create banned_ip table."
) {
    override val handlers: List<suspend (SqlClient) -> Unit> = listOf(
        addSourceColumnToBanHistoryTable(),
        createBannedIpTable()
    )

    private fun addSourceColumnToBanHistoryTable(): suspend (sqlClient: SqlClient) -> Unit =
        { sqlClient: SqlClient ->
            val tableName = "${getTablePrefix()}ban_history"

            if (!columnExists(sqlClient, tableName, "source")) {
                val query = """
                    ALTER TABLE `$tableName`
                    ADD COLUMN `source` VARCHAR(255) NULL;
                """.trimIndent()

                sqlClient.query(query).execute().coAwait()
            }
        }

    private fun createBannedIpTable(): suspend (sqlClient: SqlClient) -> Unit =
        { sqlClient: SqlClient ->
            val query = """
                CREATE TABLE IF NOT EXISTS `${getTablePrefix()}banned_ip` (
                  `id` bigint NOT NULL AUTO_INCREMENT,
                  `ip` varchar(45) NOT NULL,
                  `reason` varchar(255),
                  `bannedUntil` bigint,
                  `bannedBy` varchar(255),
                  `source` varchar(255),
                  `bannedBySystem` tinyint(1) NOT NULL DEFAULT 0,
                  `createdAt` BIGINT(20) NOT NULL,
                  `updatedAt` BIGINT(20) NOT NULL,
                  PRIMARY KEY (`id`),
                  UNIQUE KEY `uq_banned_ip_ip` (`ip`)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='IP ban list.';
            """.trimIndent()

            sqlClient.query(query).execute().coAwait()
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
