package com.panomc.platform.db.implementation

import com.panomc.platform.annotation.Dao
import com.panomc.platform.db.dao.PostViewTrackerDao
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.sqlclient.SqlClient
import io.vertx.sqlclient.Tuple

@Dao
class PostViewTrackerDaoImpl : PostViewTrackerDao() {
    override suspend fun init(sqlClient: SqlClient) {
        sqlClient
            .query(
                """
                    CREATE TABLE IF NOT EXISTS `${getTablePrefix() + tableName}` (
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

    override suspend fun tryRecordView(
        postId: Long,
        viewerHash: String,
        viewedAt: Long,
        dedupeWindowMs: Long,
        sqlClient: SqlClient
    ): Boolean {
        val cutoff = viewedAt - dedupeWindowMs
        val table = getTablePrefix() + tableName

        val updateResult = sqlClient
            .preparedQuery(
                """
                    UPDATE `$table`
                    SET `lastViewedAt` = ?, `updatedAt` = ?
                    WHERE `postId` = ? AND `viewerHash` = ? AND `lastViewedAt` <= ?
                """
            )
            .execute(
                Tuple.of(
                    viewedAt,
                    viewedAt,
                    postId,
                    viewerHash,
                    cutoff
                )
            )
            .coAwait()

        if (updateResult.rowCount() > 0) {
            return true
        }

        val insertResult = sqlClient
            .preparedQuery(
                """
                    INSERT IGNORE INTO `$table` (`postId`, `viewerHash`, `lastViewedAt`, `createdAt`, `updatedAt`)
                    VALUES (?, ?, ?, ?, ?)
                """
            )
            .execute(
                Tuple.of(
                    postId,
                    viewerHash,
                    viewedAt,
                    viewedAt,
                    viewedAt
                )
            )
            .coAwait()

        return insertResult.rowCount() > 0
    }
}
