package com.panomc.platform.db.migration

import com.panomc.platform.annotation.Migration
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.DatabaseMigration
import io.vertx.kotlin.coroutines.await
import io.vertx.sqlclient.SqlClient

@Migration
class DatabaseMigration32To33(databaseManager: DatabaseManager) : DatabaseMigration(databaseManager) {
    override val FROM_SCHEME_VERSION = 32
    override val SCHEME_VERSION = 33
    override val SCHEME_VERSION_INFO =
        "Add notification table."

    override val handlers: List<suspend (sqlClient: SqlClient) -> Unit> =
        listOf(
            createNotificationTable()
        )

    private fun createNotificationTable(): suspend (sqlClient: SqlClient) -> Unit =
        { sqlClient: SqlClient ->
            sqlClient
                .query(
                    """
                        CREATE TABLE IF NOT EXISTS `${getTablePrefix()}notification` (
                          `id` bigint NOT NULL AUTO_INCREMENT,
                          `user_id` bigint NOT NULL,
                          `type_id` varchar(255) NOT NULL,
                          `date` BIGINT(20) NOT NULL,
                          `status` varchar(255) NOT NULL,
                          PRIMARY KEY (`id`)
                        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Notification table.';
                        """
                )
                .execute()
                .await()
        }
}