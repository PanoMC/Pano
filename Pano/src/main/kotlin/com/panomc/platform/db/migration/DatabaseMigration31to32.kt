package com.panomc.platform.db.migration

import com.panomc.platform.annotation.Migration
import com.panomc.platform.db.DatabaseMigration
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.sqlclient.SqlClient

@Migration
class DatabaseMigration31to32 : DatabaseMigration(
    31,
    32,
    "Add pending_auth_session table for generic auth completion handles."
) {
    override val handlers: List<suspend (SqlClient) -> Unit> = listOf(
        createPendingAuthSessionTable()
    )

    private fun createPendingAuthSessionTable(): suspend (sqlClient: SqlClient) -> Unit =
        { sqlClient: SqlClient ->
            val query = """
                CREATE TABLE IF NOT EXISTS `${getTablePrefix()}pending_auth_session` (
                  `id` bigint NOT NULL AUTO_INCREMENT,
                  `token` varchar(128) NOT NULL,
                  `userId` bigint NOT NULL,
                  `source` varchar(64) NOT NULL,
                  `createdAt` bigint(20) NOT NULL,
                  `expiresAt` bigint(20) NOT NULL,
                  PRIMARY KEY (`id`),
                  UNIQUE KEY `uq_token` (`token`),
                  KEY `idx_expires_at` (`expiresAt`)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
            """.trimIndent()

            sqlClient
                .query(query)
                .execute()
                .coAwait()
        }
}
