package com.panomc.platform.db.implementation

import com.panomc.platform.annotation.Dao
import com.panomc.platform.db.DaoImpl
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.dao.PostCategoryDao
import com.panomc.platform.db.model.PostCategory
import io.vertx.kotlin.coroutines.await
import io.vertx.mysqlclient.MySQLClient
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.RowSet
import io.vertx.sqlclient.SqlConnection
import io.vertx.sqlclient.Tuple

@Dao
class PostCategoryDaoImpl(databaseManager: DatabaseManager) : DaoImpl(databaseManager, "post_category"),
    PostCategoryDao {

    override suspend fun init(sqlConnection: SqlConnection) {
        sqlConnection
            .query(
                """
                            CREATE TABLE IF NOT EXISTS `${getTablePrefix() + tableName}` (
                              `id` bigint NOT NULL AUTO_INCREMENT,
                              `title` MEDIUMTEXT NOT NULL,
                              `description` text NOT NULL,
                              `url` varchar(255) NOT NULL,
                              `color` varchar(6) NOT NULL,
                              PRIMARY KEY (`id`)
                            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Post category table.';
                        """
            )
            .execute()
            .await()
    }

    override suspend fun existsById(
        id: Long,
        sqlConnection: SqlConnection
    ): Boolean {
        val query =
            "SELECT COUNT(id) FROM `${getTablePrefix() + tableName}` where `id` = ?"

        val rows: RowSet<Row> = sqlConnection
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    id
                )
            ).await()

        return rows.toList()[0].getLong(0) == 1L
    }

    override suspend fun deleteById(
        id: Long,
        sqlConnection: SqlConnection,
    ) {
        val query =
            "DELETE FROM `${getTablePrefix() + tableName}` WHERE id = ?"

        sqlConnection
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    id
                )
            ).await()
    }

    override suspend fun getCount(sqlConnection: SqlConnection): Long {
        val query =
            "SELECT COUNT(id) FROM `${getTablePrefix() + tableName}`"

        val rows: RowSet<Row> = sqlConnection
            .preparedQuery(query)
            .execute()
            .await()

        return rows.toList()[0].getLong(0)
    }

    override suspend fun getByIdList(
        idList: List<Long>,
        sqlConnection: SqlConnection
    ): Map<Long, PostCategory> {
        var listText = ""

        idList.forEach { id ->
            if (listText == "")
                listText = "'$id'"
            else
                listText += ", '$id'"
        }

        val query =
            "SELECT `id`, `title`, `description`, `url`, `color` FROM `${getTablePrefix() + tableName}` WHERE  `id` IN ($listText) ORDER BY id DESC"

        val rows: RowSet<Row> = sqlConnection
            .preparedQuery(query)
            .execute()
            .await()

        return PostCategory.from(rows).associateBy { it.id }
    }

    override suspend fun getAll(
        sqlConnection: SqlConnection
    ): List<PostCategory> {
        val query =
            "SELECT `id`, `title`, `description`, `url`, `color` FROM `${getTablePrefix() + tableName}` ORDER BY id DESC"

        val rows: RowSet<Row> = sqlConnection
            .preparedQuery(query)
            .execute()
            .await()

        return PostCategory.from(rows)
    }

    override suspend fun getCategories(
        page: Long,
        sqlConnection: SqlConnection
    ): List<PostCategory> {
        val query =
            "SELECT id, title, description, url, color FROM `${getTablePrefix() + tableName}` ORDER BY id DESC LIMIT 10 OFFSET ${(page - 1) * 10}"

        val rows: RowSet<Row> = sqlConnection
            .preparedQuery(query)
            .execute()
            .await()

        return PostCategory.from(rows)
    }

    override suspend fun existsByUrl(
        url: String,
        sqlConnection: SqlConnection
    ): Boolean {
        val query =
            "SELECT COUNT(id) FROM `${getTablePrefix() + tableName}` where `url` = ?"

        val rows: RowSet<Row> = sqlConnection
            .preparedQuery(query)
            .execute(Tuple.of(url))
            .await()

        return rows.toList()[0].getLong(0) == 1L
    }

    override suspend fun existsByUrlNotById(
        url: String,
        id: Long,
        sqlConnection: SqlConnection
    ): Boolean {
        val query =
            "SELECT COUNT(id) FROM `${getTablePrefix() + tableName}` where `url` = ? and id != ?"

        val rows: RowSet<Row> = sqlConnection
            .preparedQuery(query)
            .execute(Tuple.of(url, id))
            .await()

        return rows.toList()[0].getLong(0) == 1L
    }

    override suspend fun add(
        postCategory: PostCategory,
        sqlConnection: SqlConnection
    ): Long {
        val query =
            "INSERT INTO `${getTablePrefix() + tableName}` (`title`, `description`, `url`, `color`) VALUES (?, ?, ?, ?)"

        val rows: RowSet<Row> = sqlConnection
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    postCategory.title,
                    postCategory.description,
                    postCategory.url,
                    postCategory.color.replace("#", "")
                )
            )
            .await()

        return rows.property(MySQLClient.LAST_INSERTED_ID)
    }

    override suspend fun update(
        postCategory: PostCategory,
        sqlConnection: SqlConnection
    ) {
        val query =
            "UPDATE `${getTablePrefix() + tableName}` SET title = ?, description = ?, url = ?, color = ? WHERE `id` = ?"

        sqlConnection
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    postCategory.title,
                    postCategory.description,
                    postCategory.url,
                    postCategory.color.replace("#", ""),
                    postCategory.id
                )
            ).await()
    }

    override suspend fun getById(
        id: Long,
        sqlConnection: SqlConnection
    ): PostCategory? {
        val query =
            "SELECT `id`, `title`, `description`, `url`, `color` FROM `${getTablePrefix() + tableName}` WHERE `id` = ?"

        val rows: RowSet<Row> = sqlConnection
            .preparedQuery(query)
            .execute(
                Tuple.of(id)
            )
            .await()

        if (rows.size() == 0) {
            return null
        }

        val row = rows.toList()[0]

        return PostCategory.from(row)
    }

    override suspend fun getByUrl(
        url: String,
        sqlConnection: SqlConnection
    ): PostCategory? {
        val query =
            "SELECT `id`, `title`, `description`, `url`, `color` FROM `${getTablePrefix() + tableName}` WHERE `url` = ?"

        val rows: RowSet<Row> = sqlConnection
            .preparedQuery(query)
            .execute(
                Tuple.of(url)
            )
            .await()

        if (rows.size() == 0) {
            return null
        }

        val row = rows.toList()[0]

        return PostCategory.from(row)
    }
}