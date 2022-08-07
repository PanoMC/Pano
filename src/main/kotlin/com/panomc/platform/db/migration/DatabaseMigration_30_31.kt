package com.panomc.platform.db.migration

import com.panomc.platform.annotation.Migration
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.DatabaseMigration
import io.vertx.kotlin.coroutines.await
import io.vertx.sqlclient.SqlConnection

@Suppress("ClassName")
@Migration
class DatabaseMigration_30_31(databaseManager: DatabaseManager) : DatabaseMigration(databaseManager) {
    override val FROM_SCHEME_VERSION = 30
    override val SCHEME_VERSION = 31
    override val SCHEME_VERSION_INFO =
        "Add valid token table."

    override val handlers: List<suspend (sqlConnection: SqlConnection) -> Unit> =
        listOf(
            updatePanelNotificationTableDateColumn(),
        )

    private fun updatePanelNotificationTableDateColumn(): suspend (sqlConnection: SqlConnection) -> Unit =
        { sqlConnection: SqlConnection ->
            sqlConnection
                .query(
                    """
                    CREATE TABLE IF NOT EXISTS `${getTablePrefix()}token` (
                          `id` bigint NOT NULL AUTO_INCREMENT,
                          `subject` mediumtext NOT NULL,
                          `token` mediumtext NOT NULL,
                          `type` varchar(32) NOT NULL,
                          `expire_date` bigint(20) NOT NULL,
                          PRIMARY KEY (`id`)
                        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Valid token table.';
                """.trimIndent()
                )
                .execute()
                .await()
        }
}