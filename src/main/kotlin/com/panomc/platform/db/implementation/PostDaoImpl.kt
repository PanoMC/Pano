package com.panomc.platform.db.implementation

import com.panomc.platform.annotation.Dao
import com.panomc.platform.db.DaoImpl
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.dao.PostDao
import com.panomc.platform.db.model.Post
import com.panomc.platform.util.PostStatus
import io.vertx.kotlin.coroutines.await
import io.vertx.mysqlclient.MySQLClient
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.RowSet
import io.vertx.sqlclient.SqlConnection
import io.vertx.sqlclient.Tuple

@Dao
class PostDaoImpl(databaseManager: DatabaseManager) : DaoImpl(databaseManager, "post"), PostDao {

    override suspend fun init(sqlConnection: SqlConnection) {
        sqlConnection
            .query(
                """
                            CREATE TABLE IF NOT EXISTS `${getTablePrefix() + tableName}` (
                              `id` bigint NOT NULL AUTO_INCREMENT,
                              `title` MEDIUMTEXT NOT NULL,
                              `category_id` bigint NOT NULL,
                              `writer_user_id` bigint NOT NULL,
                              `text` longblob NOT NULL,
                              `date` BIGINT(20) NOT NULL,
                              `move_date` BIGINT(20) NOT NULL,
                              `status` int(1) NOT NULL,
                              `thumbnail_url` mediumtext NOT NULL,
                              `views` BIGINT(20) NOT NULL,
                              `url` mediumtext NOT NULL,
                              PRIMARY KEY (`id`)
                            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Posts table.';
                        """
            )
            .execute()
            .await()
    }

    override suspend fun removePostCategoriesByCategoryId(
        categoryId: Long,
        sqlConnection: SqlConnection
    ) {
        val query = "UPDATE `${getTablePrefix() + tableName}` SET category_id = ? WHERE category_id = ?"

        sqlConnection
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    -1,
                    categoryId
                )
            )
            .await()
    }

    override suspend fun isExistsById(
        id: Long,
        sqlConnection: SqlConnection
    ): Boolean {
        val query = "SELECT COUNT(id) FROM `${getTablePrefix() + tableName}` where `id` = ?"

        val rows: RowSet<Row> = sqlConnection
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    id
                )
            )
            .await()

        return rows.toList()[0].getLong(0) == 1L
    }

    override suspend fun isExistsByUrl(url: String, sqlConnection: SqlConnection): Boolean {
        val query = "SELECT COUNT(id) FROM `${getTablePrefix() + tableName}` where `url` = ?"

        val rows: RowSet<Row> = sqlConnection
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    url
                )
            )
            .await()

        return rows.toList()[0].getLong(0) == 1L
    }

    override suspend fun getById(
        id: Long,
        sqlConnection: SqlConnection
    ): Post? {
        val query =
            "SELECT `id`, `title`, `category_id`, `writer_user_id`, `text`, `date`, `move_date`, `status`, `thumbnail_url`, `views`, `url` FROM `${getTablePrefix() + tableName}` WHERE  `id` = ?"

        val rows: RowSet<Row> = sqlConnection
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    id
                )
            )
            .await()

        if (rows.size() == 0) {
            return null
        }

        val row = rows.toList()[0]

        return Post.from(row)
    }

    override suspend fun getByUrl(url: String, sqlConnection: SqlConnection): Post? {
        val query =
            "SELECT `id`, `title`, `category_id`, `writer_user_id`, `text`, `date`, `move_date`, `status`, `thumbnail_url`, `views`, `url` FROM `${getTablePrefix() + tableName}` WHERE  `url` = ?"

        val rows: RowSet<Row> = sqlConnection
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    url
                )
            )
            .await()

        if (rows.size() == 0) {
            return null
        }

        val row = rows.toList()[0]

        return Post.from(row)
    }

    override suspend fun moveTrashById(
        id: Long,
        sqlConnection: SqlConnection
    ) {
        val query =
            "UPDATE `${getTablePrefix() + tableName}` SET move_date = ?, status = ? WHERE `id` = ?"

        sqlConnection
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    System.currentTimeMillis(),
                    0,
                    id
                )
            )
            .await()
    }

    override suspend fun moveDraftById(
        id: Long,
        sqlConnection: SqlConnection
    ) {
        val query =
            "UPDATE `${getTablePrefix() + tableName}` SET move_date = ?, status = ? WHERE `id` = ?"

        sqlConnection
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    System.currentTimeMillis(),
                    2,
                    id
                )
            )
            .await()
    }

    override suspend fun publishById(
        id: Long,
        userId: Long,
        sqlConnection: SqlConnection
    ) {
        val query =
            "UPDATE `${getTablePrefix() + tableName}` SET writer_user_id = ?, `date` = ?, move_date = ?, status = ? WHERE `id` = ?"

        sqlConnection
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    userId,
                    System.currentTimeMillis(),
                    System.currentTimeMillis(),
                    1,
                    id
                )
            )
            .await()
    }

    override suspend fun insert(post: Post, sqlConnection: SqlConnection): Long {
        val query =
            "INSERT INTO `${getTablePrefix() + tableName}` (`title`, `category_id`, `writer_user_id`, `text`, `date`, `move_date`, `status`, `thumbnail_url`, `views`, `url`) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"

        val rows: RowSet<Row> = sqlConnection
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    post.title,
                    post.categoryId,
                    post.writerUserId,
                    post.text,
                    post.date,
                    post.moveDate,
                    post.status.value,
                    post.thumbnailUrl,
                    post.views,
                    post.url
                )
            )
            .await()

        return rows.property(MySQLClient.LAST_INSERTED_ID)
    }

    override suspend fun update(userId: Long, post: Post, sqlConnection: SqlConnection) {
        val query =
            "UPDATE `${getTablePrefix() + tableName}` SET title = ?, category_id = ?, writer_user_id = ?, text = ?, `date` = ?, move_date = ?, status = ?, thumbnail_url = ?, `url` = ? WHERE `id` = ?"

        sqlConnection
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    post.title,
                    post.categoryId,
                    post.writerUserId,
                    post.text,
                    System.currentTimeMillis(),
                    System.currentTimeMillis(),
                    post.status.value,
                    post.thumbnailUrl,
                    post.url,
                    post.id
                )
            )
            .await()
    }

    override suspend fun updatePostUrlByUrl(url: String, newUrl: String, sqlConnection: SqlConnection) {
        val query =
            "UPDATE `${getTablePrefix() + tableName}` SET `url` = ? WHERE `url` = ?"

        sqlConnection
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    newUrl,
                    url
                )
            )
            .await()
    }

    override suspend fun count(sqlConnection: SqlConnection): Long {
        val query = "SELECT COUNT(id) FROM `${getTablePrefix() + tableName}`"

        val rows: RowSet<Row> = sqlConnection
            .preparedQuery(query)
            .execute()
            .await()

        return rows.toList()[0].getLong(0)
    }

    override suspend fun countByCategory(
        id: Long,
        sqlConnection: SqlConnection
    ): Long {
        val query = "SELECT COUNT(id) FROM `${getTablePrefix() + tableName}` where category_id = ?"

        val rows: RowSet<Row> = sqlConnection
            .preparedQuery(query)
            .execute(
                Tuple.of(id)
            )
            .await()

        return rows.toList()[0].getLong(0)
    }

    override suspend fun countByPageType(
        postStatus: PostStatus,
        sqlConnection: SqlConnection
    ): Long {
        val query =
            "SELECT COUNT(id) FROM `${getTablePrefix() + tableName}` WHERE `status` = ?"

        val rows: RowSet<Row> = sqlConnection
            .preparedQuery(query)
            .execute(
                Tuple.of(postStatus.value)
            )
            .await()

        return rows.toList()[0].getLong(0)
    }

    override suspend fun countByPageTypeAndCategoryId(
        postStatus: PostStatus,
        categoryId: Long,
        sqlConnection: SqlConnection
    ): Long {
        val query =
            "SELECT COUNT(id) FROM `${getTablePrefix() + tableName}` WHERE `status` = ? and `category_id` = ?"

        val rows: RowSet<Row> = sqlConnection
            .preparedQuery(query)
            .execute(
                Tuple.of(postStatus.value, categoryId)
            )
            .await()

        return rows.toList()[0].getLong(0)
    }

    override suspend fun delete(
        id: Long,
        sqlConnection: SqlConnection
    ) {
        val query =
            "DELETE from `${getTablePrefix() + tableName}` WHERE `id` = ?"

        sqlConnection
            .preparedQuery(query)
            .execute(
                Tuple.of(id)
            )
            .await()
    }

    override suspend fun getByCategory(
        id: Long,
        sqlConnection: SqlConnection
    ): List<Post> {
        val query =
            "SELECT id, title, category_id, writer_user_id, text, `date`, move_date, status, thumbnail_url, views, `url` FROM `${getTablePrefix() + tableName}` WHERE category_id = ? ORDER BY `date` DESC LIMIT 5"

        val rows: RowSet<Row> = sqlConnection
            .preparedQuery(query)
            .execute(
                Tuple.of(id)
            )
            .await()

        val posts = mutableListOf<Post>()

        posts.addAll(Post.from(rows))

        return posts
    }

    override suspend fun getByPageAndPageType(
        page: Long,
        postStatus: PostStatus,
        sqlConnection: SqlConnection
    ): List<Post> {
        val query =
            "SELECT `id`, `title`, `category_id`, `writer_user_id`, `text`, `date`, `move_date`, `status`, `thumbnail_url`, `views`, `url` FROM `${getTablePrefix() + tableName}` WHERE `status` = ? ORDER BY ${if (postStatus == PostStatus.PUBLISHED) "`date` DESC" else "move_date DESC"} LIMIT 10 OFFSET ${(page - 1) * 10}"

        val rows: RowSet<Row> = sqlConnection
            .preparedQuery(query)
            .execute(
                Tuple.of(postStatus.value)
            )
            .await()

        val posts = mutableListOf<Post>()

        posts.addAll(Post.from(rows))

        return posts
    }

    override suspend fun getByPagePageTypeAndCategoryId(
        page: Long,
        postStatus: PostStatus,
        categoryId: Long,
        sqlConnection: SqlConnection
    ): List<Post> {
        val query =
            "SELECT `id`, `title`, `category_id`, `writer_user_id`, `text`, `date`, `move_date`, `status`, `thumbnail_url`, `views`, `url` FROM `${getTablePrefix() + tableName}` WHERE `status` = ? and `category_id` = ? ORDER BY ${if (postStatus == PostStatus.PUBLISHED) "`date` DESC" else "move_date DESC"} LIMIT 10 OFFSET ${(page - 1) * 10}"

        val rows: RowSet<Row> = sqlConnection
            .preparedQuery(query)
            .execute(
                Tuple.of(postStatus.value, categoryId)
            )
            .await()

        return Post.from(rows)
    }

    override suspend fun countOfPublished(
        sqlConnection: SqlConnection
    ): Long {
        val query =
            "SELECT COUNT(id) FROM `${getTablePrefix() + tableName}` WHERE status = ?"

        val rows: RowSet<Row> = sqlConnection
            .preparedQuery(query)
            .execute(
                Tuple.of(PostStatus.PUBLISHED.value)
            )
            .await()

        return rows.toList()[0].getLong(0)
    }

    override suspend fun countOfPublishedByCategoryId(
        categoryId: Long,
        sqlConnection: SqlConnection
    ): Long {
        val query =
            "SELECT COUNT(`id`) FROM `${getTablePrefix() + tableName}` WHERE `status` = ? AND `category_id` = ?"

        val rows: RowSet<Row> = sqlConnection
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    PostStatus.PUBLISHED.value,
                    categoryId
                )
            )
            .await()

        return rows.toList()[0].getLong(0)
    }

    override suspend fun getPublishedListByPage(
        page: Long,
        sqlConnection: SqlConnection
    ): List<Post> {
        val query =
            "SELECT `id`, `title`, `category_id`, `writer_user_id`, `text`, `date`, `move_date`, `status`, `thumbnail_url`, `views`, `url` FROM `${getTablePrefix() + tableName}` WHERE `status` = ? ORDER BY `date` DESC LIMIT 5 OFFSET ${(page - 1) * 5}"

        val rows: RowSet<Row> = sqlConnection
            .preparedQuery(query)
            .execute(
                Tuple.of(PostStatus.PUBLISHED.value)
            )
            .await()

        return Post.from(rows)
    }

    override suspend fun getPublishedListByPageAndCategoryId(
        categoryId: Long,
        page: Long,
        sqlConnection: SqlConnection
    ): List<Post> {
        val query =
            "SELECT `id`, `title`, `category_id`, `writer_user_id`, `text`, `date`, `move_date`, `status`, `thumbnail_url`, `views`, `url` FROM `${getTablePrefix() + tableName}` WHERE `status` = ? AND `category_id` = ? ORDER BY `date` DESC LIMIT 5 OFFSET ${(page - 1) * 5}"

        val rows: RowSet<Row> = sqlConnection
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    PostStatus.PUBLISHED.value,
                    categoryId
                )
            )
            .await()

        return Post.from(rows)
    }

    override suspend fun getListByPageAndCategoryId(
        categoryId: Long,
        page: Long,
        sqlConnection: SqlConnection
    ): List<Post> {
        val query =
            "SELECT `id`, `title`, `category_id`, `writer_user_id`, `text`, `date`, `move_date`, `status`, `thumbnail_url`, `views`, `url` FROM `${getTablePrefix() + tableName}` WHERE `category_id` = ? ORDER BY `date` DESC LIMIT 10 OFFSET ${(page - 1) * 10}"

        val rows: RowSet<Row> = sqlConnection
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    categoryId
                )
            )
            .await()

        return Post.from(rows)
    }

    override suspend fun increaseViewByOne(
        id: Long,
        sqlConnection: SqlConnection
    ) {
        val query =
            "UPDATE `${getTablePrefix() + tableName}` SET `views` = `views` + 1 WHERE `id` = ?"

        sqlConnection
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    id
                )
            )
            .await()
    }

    override suspend fun increaseViewByOne(url: String, sqlConnection: SqlConnection) {
        val query =
            "UPDATE `${getTablePrefix() + tableName}` SET `views` = `views` + 1 WHERE `url` = ?"

        sqlConnection
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    url
                )
            )
            .await()
    }

    override suspend fun isPreviousPostExistsByDate(
        date: Long,
        sqlConnection: SqlConnection
    ): Boolean {
        val query =
            "SELECT count(`id`) FROM `${getTablePrefix() + tableName}` WHERE (`id`,`date`) IN ( SELECT `id`, max(`date`) FROM `${getTablePrefix() + tableName}` where `status`= ? and `date` < ? GROUP BY `id`) order by `date` DESC limit 1"

        val rows: RowSet<Row> = sqlConnection
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    PostStatus.PUBLISHED.value,
                    date
                )
            )
            .await()

        return rows.toList()[0].getLong(0) > 0L
    }

    override suspend fun isNextPostExistsByDate(
        date: Long,
        sqlConnection: SqlConnection
    ): Boolean {
        val query =
            "SELECT count(`id`) FROM `${getTablePrefix() + tableName}` WHERE (`id`,`date`) IN ( SELECT `id`, MIN(`date`) FROM `${getTablePrefix() + tableName}`where `status`= ? and `date` > ? GROUP BY `id`) order by `date` limit 1"

        val rows: RowSet<Row> = sqlConnection
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    PostStatus.PUBLISHED.value,
                    date
                )
            )
            .await()

        return rows.toList()[0].getLong(0) > 0L
    }

    override suspend fun getPreviousPostByDate(
        date: Long,
        sqlConnection: SqlConnection
    ): Post? {
        val query =
            "SELECT `id`, `title`, `category_id`, `writer_user_id`, `text`, `date`, `move_date`, `status`, `thumbnail_url`, `views`, `url` FROM `${getTablePrefix() + tableName}` WHERE (`id`,`date`) IN ( SELECT `id`, max(`date`) FROM `${getTablePrefix() + tableName}` where `status` = ? and `date` < ? GROUP BY `id` ) order by `date` DESC limit 1"

        val rows: RowSet<Row> = sqlConnection
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    PostStatus.PUBLISHED.value,
                    date
                )
            )
            .await()

        if (rows.size() == 0) {
            return null
        }

        val row = rows.toList()[0]

        return Post.from(row)
    }

    override suspend fun getNextPostByDate(
        date: Long,
        sqlConnection: SqlConnection
    ): Post? {
        val query =
            "SELECT `id`, `title`, `category_id`, `writer_user_id`, `text`, `date`, `move_date`, `status`, `thumbnail_url`, `views`, `url` FROM `${getTablePrefix() + tableName}` WHERE (`id`,`date`) IN (SELECT `id`, MIN(`date`) FROM `${getTablePrefix() + tableName}` where `status` = ? and `date` > ? GROUP BY `id` ) order by `date` limit 1"

        val rows: RowSet<Row> = sqlConnection
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    PostStatus.PUBLISHED.value,
                    date
                )
            )
            .await()

        if (rows.size() == 0) {
            return null
        }

        val row = rows.toList()[0]

        return Post.from(row)
    }
}