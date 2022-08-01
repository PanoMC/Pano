package com.panomc.platform.db.migration

import com.panomc.platform.annotation.Migration
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.DatabaseMigration
import com.panomc.platform.util.TextUtil
import io.vertx.kotlin.coroutines.await
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.RowSet
import io.vertx.sqlclient.SqlConnection
import io.vertx.sqlclient.Tuple

@Suppress("ClassName")
@Migration
class DatabaseMigration_27_28(databaseManager: DatabaseManager) : DatabaseMigration(databaseManager) {
    override val FROM_SCHEME_VERSION = 27
    override val SCHEME_VERSION = 28
    override val SCHEME_VERSION_INFO =
        "Add URL column to post table."

    override val handlers: List<suspend (sqlConnection: SqlConnection) -> Unit> =
        listOf(
            addUrlColumnToPostTable(),
            convertPostTitlesToUrl()
        )

    private fun addUrlColumnToPostTable(): suspend (sqlConnection: SqlConnection) -> Unit =
        { sqlConnection: SqlConnection ->
            sqlConnection
                .query("ALTER TABLE `${getTablePrefix()}post` ADD `url` mediumtext NOT NULL DEFAULT '';")
                .execute()
                .await()
        }

    private fun convertPostTitlesToUrl(): suspend (sqlConnection: SqlConnection) -> Unit =
        { sqlConnection: SqlConnection ->
            val rows: RowSet<Row> = sqlConnection
                .preparedQuery("SELECT `id`, `title` FROM `${getTablePrefix()}post`")
                .execute()
                .await()

            val posts = mutableMapOf<Long, String>()

            rows.forEach {
                posts[it.getLong(0)] = it.getString(1)
            }

            posts.forEach { post ->
                val query = "UPDATE `${getTablePrefix()}post` SET `url` = ? WHERE `id` = ?"

                val url = TextUtil.convertStringToUrl(post.value)

                sqlConnection
                    .preparedQuery(query)
                    .execute(
                        Tuple.of(
                            url,
                            post.key
                        )
                    ).await()
            }
        }
}