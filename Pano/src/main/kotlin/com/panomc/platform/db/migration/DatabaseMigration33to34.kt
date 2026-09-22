package com.panomc.platform.db.migration

import com.panomc.platform.annotation.Migration
import com.panomc.platform.db.DatabaseMigration
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.sqlclient.SqlClient

@Migration
class DatabaseMigration33to34 : DatabaseMigration(
    33,
    34,
    "Add server_metric table"
) {
    override val handlers: List<suspend (SqlClient) -> Unit> = listOf(
        createServerMetricTable()
    )

    private fun createServerMetricTable(): suspend (sqlClient: SqlClient) -> Unit =
        { sqlClient: SqlClient ->
            val query = """
            CREATE TABLE IF NOT EXISTS `${getTablePrefix()}server_metric` (
              `id` bigint NOT NULL AUTO_INCREMENT,
              `serverId` bigint NOT NULL,
              `ts` bigint NOT NULL,
              `tps` double DEFAULT NULL,
              `mspt` double DEFAULT NULL,
              `memUsed` bigint NOT NULL DEFAULT 0,
              `memMax` bigint NOT NULL DEFAULT 0,
              `cpu` double DEFAULT NULL,
              `players` bigint NOT NULL DEFAULT 0,
              PRIMARY KEY (`id`),
              KEY `idx_server_metric_server_ts` (`serverId`, `ts`)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Per minute server performance history.';
        """.trimIndent()

            sqlClient.preparedQuery(query).execute().coAwait()
        }
}
