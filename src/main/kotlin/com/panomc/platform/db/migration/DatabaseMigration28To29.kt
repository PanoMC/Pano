package com.panomc.platform.db.migration

import com.panomc.platform.annotation.Migration
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.DatabaseMigration
import io.vertx.kotlin.coroutines.await
import io.vertx.sqlclient.SqlConnection

@Migration
class DatabaseMigration28To29(databaseManager: DatabaseManager) : DatabaseMigration(databaseManager) {
    override val FROM_SCHEME_VERSION = 28
    override val SCHEME_VERSION = 29
    override val SCHEME_VERSION_INFO =
        "Add website visitor view table."

    override val handlers: List<suspend (sqlConnection: SqlConnection) -> Unit> =
        listOf(
            createWebsiteVisitorViewTable()
        )

    private fun createWebsiteVisitorViewTable(): suspend (sqlConnection: SqlConnection) -> Unit =
        { sqlConnection: SqlConnection ->
            sqlConnection
                .query(
                    """
                        CREATE TABLE IF NOT EXISTS `${getTablePrefix()}website_view` (
                          `id` bigint NOT NULL AUTO_INCREMENT,
                          `times` bigint NOT NULL,
                          `date` varchar(255) NOT NULL,
                          `ip_address` varchar(255) NOT NULL,
                          PRIMARY KEY (`id`)
                        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Website visitor view table.';
                        """
                )
                .execute()
                .await()
        }

}