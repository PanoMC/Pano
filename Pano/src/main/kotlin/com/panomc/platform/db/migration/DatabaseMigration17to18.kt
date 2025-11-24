package com.panomc.platform.db.migration

import com.panomc.platform.annotation.Migration
import com.panomc.platform.db.DatabaseMigration
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.sqlclient.SqlClient

@Migration
class DatabaseMigration17to18 : DatabaseMigration(
    17,
    18,
    "Add banMessage and bannedUntil columns to user table & create ban history table."
) {
    override val handlers: List<suspend (SqlClient) -> Unit> = listOf(
        addBanMessageColumnToUserTable(),
        addBannedUntilColumnToUserTable(),
        createBanHistoryTable()
    )

    private fun addBanMessageColumnToUserTable(): suspend (sqlClient: SqlClient) -> Unit =
        { sqlClient: SqlClient ->
            val addColumnQuery = """
                ALTER TABLE `${getTablePrefix()}user`
                ADD COLUMN `banMessage` VARCHAR(255) NULL;
            """.trimIndent()

            sqlClient.preparedQuery(addColumnQuery).execute().coAwait()
        }

    private fun addBannedUntilColumnToUserTable(): suspend (sqlClient: SqlClient) -> Unit =
        { sqlClient: SqlClient ->
            val addColumnQuery = """
                ALTER TABLE `${getTablePrefix()}user`
                ADD COLUMN `bannedUntil` BIGINT NULL;
            """.trimIndent()

            sqlClient.preparedQuery(addColumnQuery).execute().coAwait()
        }

    private fun createBanHistoryTable(): suspend (sqlClient: SqlClient) -> Unit =
        { sqlClient: SqlClient ->
            val createTableQuery = """
                CREATE TABLE IF NOT EXISTS `${getTablePrefix()}ban_history` (
                  `id` bigint NOT NULL AUTO_INCREMENT,
                  `userId` bigint,
                  `reason` varchar(255),
                  `emailNotified` tinyint(1),
                  `bannedUntil` bigint,
                  `bannedBy` varchar(255),
                  `bannedBySystem` tinyint(1) DEFAULT 0,
                  `createdAt` BIGINT(20) NOT NULL,
                  `updatedAt` BIGINT(20) NOT NULL,
                  PRIMARY KEY (`id`)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Ban history table.';
            """.trimIndent()

            sqlClient.preparedQuery(createTableQuery).execute().coAwait()
        }
}