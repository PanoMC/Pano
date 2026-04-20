package com.panomc.platform.db.migration

import com.panomc.platform.annotation.Migration
import com.panomc.platform.db.DatabaseMigration
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.sqlclient.SqlClient

@Migration
class DatabaseMigration28to29 : DatabaseMigration(28, 29, "Create post_view_tracker table") {
    override val handlers: List<suspend (sqlClient: SqlClient) -> Unit> = listOf(
        { sqlClient ->
            val table = "${getTablePrefix()}post_view_tracker"

            sqlClient
                .query(
                    """
                        CREATE TABLE IF NOT EXISTS `$table` (
                          `id` bigint NOT NULL AUTO_INCREMENT,
                          `postId` bigint NOT NULL,
                          `viewerHash` varchar(64) NOT NULL,
                          `lastViewedAt` BIGINT(20) NOT NULL,
                          `createdAt` BIGINT(20) NOT NULL,
                          `updatedAt` BIGINT(20) NOT NULL,
                          PRIMARY KEY (`id`),
                          UNIQUE KEY `uq_post_view_tracker_post_viewer` (`postId`, `viewerHash`),
                          KEY `idx_post_view_tracker_updated_at` (`updatedAt`)
                        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Post view deduplication table.';
                    """
                )
                .execute()
                .coAwait()
        }
    )
}
