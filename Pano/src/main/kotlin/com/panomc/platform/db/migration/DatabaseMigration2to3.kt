package com.panomc.platform.db.migration

import com.panomc.platform.annotation.Migration
import com.panomc.platform.db.DatabaseMigration
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.sqlclient.SqlClient

@Migration
class DatabaseMigration2to3 : DatabaseMigration(2, 3, "Add panel activity log table") {
    override val handlers: List<suspend (SqlClient) -> Unit> = listOf(
        createPanelActivityLogTable()
    )

    private fun createPanelActivityLogTable(): suspend (sqlClient: SqlClient) -> Unit =
        { sqlClient: SqlClient ->
            sqlClient
                .query(
                    """
                            CREATE TABLE IF NOT EXISTS `${getTablePrefix()}panel_activity_log` (
                              `id` bigint NOT NULL AUTO_INCREMENT,
                              `userId` bigint,
                              `type` varchar(255) NOT NULL,
                              `details` mediumtext NOT NULL,
                              `createdAt` BIGINT(20) NOT NULL,
                              `updatedAt` BIGINT(20) NOT NULL,
                              PRIMARY KEY (`id`)
                            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Panel activity log table.';
                """.trimIndent()
                )
                .execute()
                .coAwait()
        }

}