package com.panomc.platform.db.migration

import com.panomc.platform.annotation.Migration
import com.panomc.platform.db.DatabaseMigration
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.sqlclient.SqlClient

@Migration
class DatabaseMigration27to28 : DatabaseMigration(27, 28, "Create online_player_history table") {
    override val handlers: List<suspend (sqlClient: SqlClient) -> Unit> = listOf(
        { sqlClient ->
            val table = "${getTablePrefix()}online_player_history"

            sqlClient
                .query(
                    """
                        CREATE TABLE IF NOT EXISTS `$table` (
                          `id` bigint NOT NULL AUTO_INCREMENT,
                          `date` BIGINT(20) NOT NULL,
                          `maxCount` BIGINT NOT NULL DEFAULT 0,
                          `sumCount` BIGINT NOT NULL DEFAULT 0,
                          `sampleCount` BIGINT NOT NULL DEFAULT 0,
                          `lastRecordedAt` BIGINT(20) NOT NULL,
                          PRIMARY KEY (`id`),
                          UNIQUE KEY `uq_online_player_history_date` (`date`)
                        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Daily online player snapshot history.';
                    """
                )
                .execute()
                .coAwait()
        }
    )
}
