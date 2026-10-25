package com.panomc.platform.db.implementation

import com.panomc.platform.annotation.Dao
import com.panomc.platform.db.dao.PostAutosaveDao
import com.panomc.platform.db.model.PostAutosave
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.SqlClient
import io.vertx.sqlclient.Tuple

@Dao
class PostAutosaveDaoImpl : PostAutosaveDao() {
    private val table get() = getTablePrefix() + tableName

    override suspend fun init(sqlClient: SqlClient) {
        sqlClient
            .query(createTableQuery(table))
            .execute()
            .coAwait()
    }

    override suspend fun getByPostId(postId: Long, sqlClient: SqlClient): PostAutosave? {
        val rows = sqlClient
            .preparedQuery("SELECT $SELECT_COLUMNS FROM `$table` WHERE `post_id` = ?")
            .execute(Tuple.of(postId))
            .coAwait()

        return rows.firstOrNull()?.toAutosave()
    }

    override suspend fun getSummariesByPostIds(postIds: List<Long>, sqlClient: SqlClient): Map<Long, PostAutosave> {
        if (postIds.isEmpty()) {
            return mapOf()
        }

        val placeholders = postIds.joinToString(", ") { "?" }
        val columns = SELECT_COLUMNS.replace("`text`", "'' AS `text`")

        val rows = sqlClient
            .preparedQuery("SELECT $columns FROM `$table` WHERE `post_id` IN ($placeholders)")
            .execute(Tuple.wrap(postIds))
            .coAwait()

        return rows.map { it.toAutosave() }.associateBy { it.postId }
    }

    override suspend fun upsert(autosave: PostAutosave, sqlClient: SqlClient) {
        val query =
            "INSERT INTO `$table` (`post_id`, `user_id`, `title`, `text`, `category_id`, `thumbnail_url`, `remove_thumbnail`, `updated_at`) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?) " +
                    "ON DUPLICATE KEY UPDATE `user_id` = VALUES(`user_id`), `title` = VALUES(`title`), `text` = VALUES(`text`), " +
                    "`category_id` = VALUES(`category_id`), `thumbnail_url` = VALUES(`thumbnail_url`), " +
                    "`remove_thumbnail` = VALUES(`remove_thumbnail`), `updated_at` = VALUES(`updated_at`)"

        sqlClient
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    autosave.postId,
                    autosave.userId,
                    autosave.title,
                    autosave.text,
                    autosave.categoryId,
                    autosave.thumbnailUrl,
                    autosave.removeThumbnail,
                    autosave.updatedAt
                )
            )
            .coAwait()
    }

    override suspend fun deleteByPostId(postId: Long, sqlClient: SqlClient) {
        sqlClient
            .preparedQuery("DELETE FROM `$table` WHERE `post_id` = ?")
            .execute(Tuple.of(postId))
            .coAwait()
    }

    // `remove_thumbnail` comes back as a number, which the JSON mapping of the other DAOs cannot read as a boolean.
    private fun Row.toAutosave() = PostAutosave(
        id = getLong("id"),
        postId = getLong("postId"),
        userId = getLong("userId"),
        title = getString("title"),
        text = getString("text"),
        categoryId = getLong("categoryId"),
        thumbnailUrl = getString("thumbnailUrl"),
        removeThumbnail = getValue("removeThumbnail").let { it == true || (it is Number && it.toInt() != 0) },
        updatedAt = getLong("updatedAt")
    )

    companion object {
        private const val SELECT_COLUMNS =
            "`id`, `post_id` AS `postId`, `user_id` AS `userId`, `title`, `text`, `category_id` AS `categoryId`, " +
                    "`thumbnail_url` AS `thumbnailUrl`, `remove_thumbnail` AS `removeThumbnail`, `updated_at` AS `updatedAt`"

        /** Shared with `DatabaseMigration63to64`, so a fresh install and an upgrade get one table. */
        fun createTableQuery(table: String) = """
            CREATE TABLE IF NOT EXISTS `$table` (
              `id` bigint NOT NULL AUTO_INCREMENT,
              `post_id` bigint NOT NULL,
              `user_id` bigint NOT NULL,
              `title` mediumtext NOT NULL,
              `text` longtext NOT NULL,
              `category_id` bigint NOT NULL,
              `thumbnail_url` mediumtext NOT NULL,
              `remove_thumbnail` tinyint(1) NOT NULL DEFAULT 0,
              `updated_at` bigint NOT NULL,
              PRIMARY KEY (`id`),
              UNIQUE KEY `idx_post_autosave_post` (`post_id`)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Unsaved changes of posts, written by the post editor.';
        """.trimIndent()
    }
}
