package com.panomc.platform.db.migration

import com.panomc.platform.annotation.Migration
import com.panomc.platform.db.DatabaseMigration
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.sqlclient.SqlClient

@Migration
class DatabaseMigration37to38 : DatabaseMigration(
    37,
    38,
    "Add the server_schedule and server_schedule_task tables"
) {
    override val handlers: List<suspend (SqlClient) -> Unit> = listOf(
        createServerScheduleTable(),
        createServerScheduleTaskTable()
    )

    private fun createServerScheduleTable(): suspend (sqlClient: SqlClient) -> Unit =
        { sqlClient: SqlClient ->
            val query = """
            CREATE TABLE IF NOT EXISTS `${getTablePrefix()}server_schedule` (
              `id` bigint NOT NULL AUTO_INCREMENT,
              `uuid` varchar(36) NOT NULL,
              `serverId` bigint NOT NULL,
              `name` varchar(255) NOT NULL,
              `cron` varchar(100) NOT NULL,
              `timezone` varchar(64) NOT NULL,
              `enabled` tinyint(1) NOT NULL DEFAULT 1,
              `warnMinutes` int NOT NULL DEFAULT 0,
              `lastRunAt` bigint DEFAULT NULL,
              `lastStatus` varchar(16) DEFAULT NULL,
              `lastError` text DEFAULT NULL,
              `nextRunAt` bigint DEFAULT NULL,
              `createdBy` bigint NOT NULL,
              `createdAt` bigint NOT NULL,
              `updatedAt` bigint NOT NULL,
              PRIMARY KEY (`id`),
              UNIQUE KEY `idx_server_schedule_uuid` (`uuid`),
              KEY `idx_server_schedule_server` (`serverId`)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Recurring jobs on a server.';
        """.trimIndent()

            sqlClient.preparedQuery(query).execute().coAwait()
        }

    private fun createServerScheduleTaskTable(): suspend (sqlClient: SqlClient) -> Unit =
        { sqlClient: SqlClient ->
            val query = """
            CREATE TABLE IF NOT EXISTS `${getTablePrefix()}server_schedule_task` (
              `id` bigint NOT NULL AUTO_INCREMENT,
              `scheduleId` bigint NOT NULL,
              `position` int NOT NULL DEFAULT 0,
              `kind` varchar(16) NOT NULL,
              `payload` text NOT NULL,
              PRIMARY KEY (`id`),
              KEY `idx_server_schedule_task_schedule` (`scheduleId`)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Steps of a server schedule.';
        """.trimIndent()

            sqlClient.preparedQuery(query).execute().coAwait()
        }
}
