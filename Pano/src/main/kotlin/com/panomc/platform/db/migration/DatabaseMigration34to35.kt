package com.panomc.platform.db.migration

import com.panomc.platform.annotation.Migration
import com.panomc.platform.db.DatabaseMigration
import com.panomc.platform.server.ServerUuidBackfill
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.sqlclient.SqlClient
import io.vertx.sqlclient.Tuple

@Migration
class DatabaseMigration34to35 : DatabaseMigration(
    34,
    35,
    "Add node and server_task tables and managed server columns"
) {
    override val handlers: List<suspend (SqlClient) -> Unit> = listOf(
        createNodeTable(),
        createServerTaskTable(),
        addManagedColumnsToServerTable(),
        fillJvmArgsOfExistingServers(),
        fillUuidOfExistingServers(),
        addUuidIndexToServerTable(),
        addNodeIndexToServerTable()
    )

    private fun createNodeTable(): suspend (sqlClient: SqlClient) -> Unit =
        { sqlClient: SqlClient ->
            val query = """
            CREATE TABLE IF NOT EXISTS `${getTablePrefix()}node` (
              `id` bigint NOT NULL AUTO_INCREMENT,
              `uuid` varchar(36) NOT NULL,
              `name` varchar(255) NOT NULL,
              `kind` varchar(16) NOT NULL,
              `runtime` varchar(16) NOT NULL DEFAULT 'PROCESS',
              `status` varchar(16) NOT NULL DEFAULT 'OFFLINE',
              `approved` TINYINT(1) NOT NULL DEFAULT 0,
              `version` varchar(64) DEFAULT NULL,
              `protocolVersion` int NOT NULL DEFAULT 1,
              `os` varchar(64) DEFAULT NULL,
              `arch` varchar(32) DEFAULT NULL,
              `hostname` varchar(255) DEFAULT NULL,
              `remoteAddress` varchar(255) DEFAULT NULL,
              `dataPath` varchar(512) DEFAULT NULL,
              `aesKey` text NOT NULL,
              `resources` text DEFAULT NULL,
              `addedTime` bigint NOT NULL,
              `acceptedTime` bigint NOT NULL DEFAULT 0,
              `lastSeen` bigint NOT NULL DEFAULT 0,
              PRIMARY KEY (`id`),
              UNIQUE KEY `idx_node_uuid` (`uuid`)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Hosts running the Pano node daemon.';
        """.trimIndent()

            sqlClient.preparedQuery(query).execute().coAwait()
        }

    private fun createServerTaskTable(): suspend (sqlClient: SqlClient) -> Unit =
        { sqlClient: SqlClient ->
            val query = """
            CREATE TABLE IF NOT EXISTS `${getTablePrefix()}server_task` (
              `id` bigint NOT NULL AUTO_INCREMENT,
              `uuid` varchar(36) NOT NULL,
              `serverId` bigint DEFAULT NULL,
              `nodeId` bigint NOT NULL,
              `kind` varchar(32) NOT NULL,
              `status` varchar(16) NOT NULL DEFAULT 'PENDING',
              `percent` int NOT NULL DEFAULT 0,
              `message` text DEFAULT NULL,
              `error` text DEFAULT NULL,
              `createdBy` bigint NOT NULL,
              `createdAt` bigint NOT NULL,
              `updatedAt` bigint NOT NULL,
              PRIMARY KEY (`id`),
              UNIQUE KEY `idx_server_task_uuid` (`uuid`),
              KEY `idx_server_task_server` (`serverId`),
              KEY `idx_server_task_node` (`nodeId`)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Long running node jobs.';
        """.trimIndent()

            sqlClient.preparedQuery(query).execute().coAwait()
        }

    // One statement rather than thirteen: MariaDB rewrites the table once instead of once per
    // column, which on a server table with history behind it is the difference between a blink
    // and a visible pause during boot.
    private fun addManagedColumnsToServerTable(): suspend (sqlClient: SqlClient) -> Unit =
        { sqlClient: SqlClient ->
            val query = """
            ALTER TABLE `${getTablePrefix()}server`
            ADD COLUMN `kind` VARCHAR(16) NOT NULL DEFAULT 'LINKED',
            ADD COLUMN `nodeId` BIGINT NULL,
            ADD COLUMN `uuid` VARCHAR(36) NULL,
            ADD COLUMN `software` VARCHAR(32) NULL,
            ADD COLUMN `softwareVersion` VARCHAR(64) NULL,
            ADD COLUMN `javaVersion` INT NULL,
            ADD COLUMN `memoryMb` INT NULL,
            ADD COLUMN `jvmArgs` TEXT NULL,
            ADD COLUMN `gamePort` INT NULL,
            ADD COLUMN `autoStart` TINYINT(1) NOT NULL DEFAULT 0,
            ADD COLUMN `crashRestart` TINYINT(1) NOT NULL DEFAULT 1,
            ADD COLUMN `lastExitCode` INT NULL,
            ADD COLUMN `processState` VARCHAR(16) NULL;
        """.trimIndent()

            sqlClient.preparedQuery(query).execute().coAwait()
        }

    private fun fillJvmArgsOfExistingServers(): suspend (sqlClient: SqlClient) -> Unit =
        { sqlClient: SqlClient ->
            val query = """
            UPDATE `${getTablePrefix()}server`
            SET `jvmArgs` = '[]'
            WHERE `jvmArgs` IS NULL;
        """.trimIndent()

            sqlClient.preparedQuery(query).execute().coAwait()
        }

    // Done row by row in Kotlin rather than with MariaDB's UUID(): the uuid has to be unique
    // across the table because a node addresses servers by it, and generating it here keeps the
    // one rule that matters testable (see ServerUuidBackfill) instead of hidden in SQL.
    private fun fillUuidOfExistingServers(): suspend (sqlClient: SqlClient) -> Unit =
        { sqlClient: SqlClient ->
            val selectQuery = "SELECT `id` FROM `${getTablePrefix()}server` WHERE `uuid` IS NULL"

            val rows = sqlClient.preparedQuery(selectQuery).execute().coAwait()

            val ids = rows.map { it.getLong(0) }

            val updateQuery = "UPDATE `${getTablePrefix()}server` SET `uuid` = ? WHERE `id` = ?"

            ServerUuidBackfill.assign(ids).forEach { (id, uuid) ->
                sqlClient.preparedQuery(updateQuery).execute(Tuple.of(uuid, id)).coAwait()
            }
        }

    private fun addUuidIndexToServerTable(): suspend (sqlClient: SqlClient) -> Unit =
        { sqlClient: SqlClient ->
            val query = """
            ALTER TABLE `${getTablePrefix()}server`
            ADD UNIQUE KEY `idx_server_uuid` (`uuid`);
        """.trimIndent()

            sqlClient.preparedQuery(query).execute().coAwait()
        }

    private fun addNodeIndexToServerTable(): suspend (sqlClient: SqlClient) -> Unit =
        { sqlClient: SqlClient ->
            val query = """
            ALTER TABLE `${getTablePrefix()}server`
            ADD KEY `idx_server_node` (`nodeId`);
        """.trimIndent()

            sqlClient.preparedQuery(query).execute().coAwait()
        }
}
