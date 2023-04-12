package com.panomc.platform.db.migration

import com.panomc.platform.annotation.Migration
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.DatabaseMigration
import io.vertx.kotlin.coroutines.await
import io.vertx.sqlclient.SqlClient

@Migration
class DatabaseMigration40To41(databaseManager: DatabaseManager) : DatabaseMigration(databaseManager) {
    override val FROM_SCHEME_VERSION = 40
    override val SCHEME_VERSION = 41
    override val SCHEME_VERSION_INFO = "Create server player table."

    override val handlers: List<suspend (sqlClient: SqlClient) -> Unit> =
        listOf(
            createServerPlayerTable(),
        )

    private fun createServerPlayerTable(): suspend (sqlClient: SqlClient) -> Unit =
        { sqlClient: SqlClient ->
            sqlClient
                .query(
                    """
                            CREATE TABLE IF NOT EXISTS `${getTablePrefix()}server_player` (
                              `id` bigint NOT NULL AUTO_INCREMENT,
                              `uuid` varchar(255) NOT NULL,
                              `username` varchar(255) NOT NULL,
                              `ping` bigint NOT NULL,
                              `server_id` bigint NOT NULL,
                              `login_time` bigint NOT NULL,
                              PRIMARY KEY (`id`)
                            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Server player table.';
                        """
                )
                .execute()
                .await()
        }
}