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
                              `id` int NOT NULL AUTO_INCREMENT,
                              `title` MEDIUMTEXT NOT NULL,
                              `category_id` int(11) NOT NULL,
                              `writer_user_id` int(11) NOT NULL,
                              `text` longblob NOT NULL,
                              `date` BIGINT(20) NOT NULL,
                              `move_date` BIGINT(20) NOT NULL,
                              `status` int(1) NOT NULL,
                              `image` longblob NOT NULL,
                              `views` BIGINT(20) NOT NULL,
                              PRIMARY KEY (`id`)
                            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Posts table.';
                        """
            )
            .execute()
            .await()
    }

    override suspend fun removePostCategoriesByCategoryId(
        categoryId: Int,
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
        id: Int,
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

        return rows.toList()[0].getInteger(0) == 1
    }

    override suspend fun getById(
        id: Int,
        sqlConnection: SqlConnection
    ): Post? {
        val query =
            "SELECT `id`, `title`, `category_id`, `writer_user_id`, `text`, `date`, `move_date`, `status`, `image`, `views` FROM `${getTablePrefix() + tableName}` WHERE  `id` = ?"

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

        val post = Post(
            row.getInteger(0),
            row.getString(1),
            row.getInteger(2),
            row.getInteger(3),
            row.getBuffer(4).toString(),
            row.getLong(5),
            row.getLong(6),
            row.getInteger(7),
            row.getBuffer(8).toString(),
            row.getLong(9)
        )

        return post
    }

    override suspend fun moveTrashById(
        id: Int,
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
        id: Int,
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
        id: Int,
        userId: Int,
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

    override suspend fun insertAndPublish(
        post: Post,
        sqlConnection: SqlConnection
    ): Long {
        val query =
            "INSERT INTO `${getTablePrefix() + tableName}` (`title`, `category_id`, `writer_user_id`, `text`, `date`, `move_date`, `status`, `image`, `views`) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)"

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
                    post.status,
                    post.image,
                    post.views
                )
            )
            .await()

        return rows.property(MySQLClient.LAST_INSERTED_ID)
    }

    override suspend fun updateAndPublish(
        userId: Int,
        post: Post,
        sqlConnection: SqlConnection
    ) {
        val query =
            "UPDATE `${getTablePrefix() + tableName}` SET title = ?, category_id = ?, writer_user_id = ?, text = ?, `date` = ?, move_date = ?, status = ?, image = ? WHERE `id` = ?"

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
                    1,
                    post.image,
                    post.id
                )
            )
            .await()
    }

    override suspend fun count(sqlConnection: SqlConnection): Int {
        val query = "SELECT COUNT(id) FROM `${getTablePrefix() + tableName}`"

        val rows: RowSet<Row> = sqlConnection
            .preparedQuery(query)
            .execute()
            .await()

        return rows.toList()[0].getInteger(0)
    }

    override suspend fun countByCategory(
        id: Int,
        sqlConnection: SqlConnection
    ): Int {
        val query = "SELECT COUNT(id) FROM `${getTablePrefix() + tableName}` where category_id = ?"

        val rows: RowSet<Row> = sqlConnection
            .preparedQuery(query)
            .execute(
                Tuple.of(id)
            )
            .await()

        return rows.toList()[0].getInteger(0)
    }

    override suspend fun countByPageType(
        postStatus: PostStatus,
        sqlConnection: SqlConnection
    ): Int {
        val query =
            "SELECT COUNT(id) FROM `${getTablePrefix() + tableName}` WHERE `status` = ?"

        val rows: RowSet<Row> = sqlConnection
            .preparedQuery(query)
            .execute(
                Tuple.of(postStatus.value)
            )
            .await()

        return rows.toList()[0].getInteger(0)
    }

    override suspend fun countByPageTypeAndCategoryId(
        postStatus: PostStatus,
        categoryId: Int,
        sqlConnection: SqlConnection
    ): Int {
        val query =
            "SELECT COUNT(id) FROM `${getTablePrefix() + tableName}` WHERE `status` = ? and `category_id` = ?"

        val rows: RowSet<Row> = sqlConnection
            .preparedQuery(query)
            .execute(
                Tuple.of(postStatus.value, categoryId)
            )
            .await()

        return rows.toList()[0].getInteger(0)
    }

    override suspend fun delete(
        id: Int,
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
        id: Int,
        sqlConnection: SqlConnection
    ): List<Post> {
        val query =
            "SELECT id, title, category_id, writer_user_id, text, `date`, move_date, status, image, views FROM `${getTablePrefix() + tableName}` WHERE category_id = ? ORDER BY `date` DESC LIMIT 5"

        val rows: RowSet<Row> = sqlConnection
            .preparedQuery(query)
            .execute(
                Tuple.of(id)
            )
            .await()

        val posts = mutableListOf<Post>()

        rows.forEach { row ->
            posts.add(
                Post(
                    row.getInteger(0),
                    row.getString(1),
                    row.getInteger(2),
                    row.getInteger(3),
                    row.getBuffer(4).toString(),
                    row.getLong(5),
                    row.getLong(6),
                    row.getInteger(7),
                    row.getBuffer(8).toString(),
                    row.getLong(9)
                )
            )
        }

        return posts
    }

    override suspend fun getByPageAndPageType(
        page: Int,
        postStatus: PostStatus,
        sqlConnection: SqlConnection
    ): List<Post> {
        val query =
            "SELECT `id`, `title`, `category_id`, `writer_user_id`, `text`, `date`, `move_date`, `status`, `image`, `views` FROM `${getTablePrefix() + tableName}` WHERE `status` = ? ORDER BY ${if (postStatus == PostStatus.PUBLISHED) "`date` DESC" else "move_date DESC"} LIMIT 10 OFFSET ${(page - 1) * 10}"

        val rows: RowSet<Row> = sqlConnection
            .preparedQuery(query)
            .execute(
                Tuple.of(postStatus.value)
            )
            .await()

        val posts = mutableListOf<Post>()

        rows.forEach { row ->
            posts.add(
                Post(
                    row.getInteger(0),
                    row.getString(1),
                    row.getInteger(2),
                    row.getInteger(3),
                    row.getBuffer(4).toString(),
                    row.getLong(5),
                    row.getLong(6),
                    row.getInteger(7),
                    row.getBuffer(8).toString(),
                    row.getLong(9)
                )
            )
        }

        return posts
    }

    override suspend fun getByPagePageTypeAndCategoryId(
        page: Int,
        postStatus: PostStatus,
        categoryId: Int,
        sqlConnection: SqlConnection
    ): List<Post> {
        val query =
            "SELECT `id`, `title`, `category_id`, `writer_user_id`, `text`, `date`, `move_date`, `status`, `image`, `views` FROM `${getTablePrefix() + tableName}` WHERE `status` = ? and `category_id` = ? ORDER BY ${if (postStatus == PostStatus.PUBLISHED) "`date` DESC" else "move_date DESC"} LIMIT 10 OFFSET ${(page - 1) * 10}"

        val rows: RowSet<Row> = sqlConnection
            .preparedQuery(query)
            .execute(
                Tuple.of(postStatus.value, categoryId)
            )
            .await()

        val posts = mutableListOf<Post>()

        rows.forEach { row ->
            posts.add(
                Post(
                    row.getInteger(0),
                    row.getString(1),
                    row.getInteger(2),
                    row.getInteger(3),
                    row.getBuffer(4).toString(),
                    row.getLong(5),
                    row.getLong(6),
                    row.getInteger(7),
                    row.getBuffer(8).toString(),
                    row.getLong(9)
                )
            )
        }

        return posts
    }

    override suspend fun countOfPublished(
        sqlConnection: SqlConnection
    ): Int {
        val query =
            "SELECT COUNT(id) FROM `${getTablePrefix() + tableName}` WHERE status = ?"

        val rows: RowSet<Row> = sqlConnection
            .preparedQuery(query)
            .execute(
                Tuple.of(PostStatus.PUBLISHED.value)
            )
            .await()

        return rows.toList()[0].getInteger(0)
    }

    override suspend fun countOfPublishedByCategoryId(
        categoryId: Int,
        sqlConnection: SqlConnection
    ): Int {
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

        return rows.toList()[0].getInteger(0)
    }

    override suspend fun getPublishedListByPage(
        page: Int,
        sqlConnection: SqlConnection
    ): List<Post> {
        val query =
            "SELECT `id`, `title`, `category_id`, `writer_user_id`, `text`, `date`, `move_date`, `status`, `image`, `views` FROM `${getTablePrefix() + tableName}` WHERE `status` = ? ORDER BY `date` DESC LIMIT 5 OFFSET ${(page - 1) * 5}"

        val rows: RowSet<Row> = sqlConnection
            .preparedQuery(query)
            .execute(
                Tuple.of(PostStatus.PUBLISHED.value)
            )
            .await()

        val posts = mutableListOf<Post>()

        rows.forEach { row ->
            posts.add(
                Post(
                    row.getInteger(0),
                    row.getString(1),
                    row.getInteger(2),
                    row.getInteger(3),
                    row.getBuffer(4).toString(),
                    row.getLong(5),
                    row.getLong(6),
                    row.getInteger(7),
                    row.getBuffer(8).toString(),
                    row.getLong(9)
                )
            )
        }

        return posts
    }

    override suspend fun getPublishedListByPageAndCategoryId(
        categoryId: Int,
        page: Int,
        sqlConnection: SqlConnection
    ): List<Post> {
        val query =
            "SELECT `id`, `title`, `category_id`, `writer_user_id`, `text`, `date`, `move_date`, `status`, `image`, `views` FROM `${getTablePrefix() + tableName}` WHERE `status` = ? AND `category_id` = ? ORDER BY `date` DESC LIMIT 5 OFFSET ${(page - 1) * 5}"

        val rows: RowSet<Row> = sqlConnection
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    PostStatus.PUBLISHED.value,
                    categoryId
                )
            )
            .await()

        val posts = mutableListOf<Post>()

        rows.forEach { row ->
            posts.add(
                Post(
                    row.getInteger(0),
                    row.getString(1),
                    row.getInteger(2),
                    row.getInteger(3),
                    row.getBuffer(4).toString(),
                    row.getLong(5),
                    row.getLong(6),
                    row.getInteger(7),
                    row.getBuffer(8).toString(),
                    row.getLong(9)
                )
            )
        }

        return posts
    }

    override suspend fun getListByPageAndCategoryId(
        categoryId: Int,
        page: Int,
        sqlConnection: SqlConnection
    ): List<Post> {
        val query =
            "SELECT `id`, `title`, `category_id`, `writer_user_id`, `text`, `date`, `move_date`, `status`, `image`, `views` FROM `${getTablePrefix() + tableName}` WHERE `category_id` = ? ORDER BY `date` DESC LIMIT 10 OFFSET ${(page - 1) * 10}"

        val rows: RowSet<Row> = sqlConnection
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    categoryId
                )
            )
            .await()

        val posts = mutableListOf<Post>()

        rows.forEach { row ->
            posts.add(
                Post(
                    row.getInteger(0),
                    row.getString(1),
                    row.getInteger(2),
                    row.getInteger(3),
                    row.getBuffer(4).toString(),
                    row.getLong(5),
                    row.getLong(6),
                    row.getInteger(7),
                    row.getBuffer(8).toString(),
                    row.getLong(9)
                )
            )
        }

        return posts
    }

    override suspend fun increaseViewByOne(
        id: Int,
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

        return rows.toList()[0].getInteger(0) > 0
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

        return rows.toList()[0].getInteger(0) > 0
    }

    override suspend fun getPreviousPostByDate(
        date: Long,
        sqlConnection: SqlConnection
    ): Post? {
        val query =
            "SELECT `id`, `title`, `category_id`, `writer_user_id`, `text`, `date`, `move_date`, `status`, `image`, `views` FROM `${getTablePrefix() + tableName}` WHERE (`id`,`date`) IN ( SELECT `id`, max(`date`) FROM `${getTablePrefix() + tableName}` where `status` = ? and `date` < ? GROUP BY `id` ) order by `date` DESC limit 1"

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

        val post = Post(
            row.getInteger(0),
            row.getString(1),
            row.getInteger(2),
            row.getInteger(3),
            row.getBuffer(4).toString(),
            row.getLong(5),
            row.getLong(6),
            row.getInteger(7),
            row.getBuffer(8).toString(),
            row.getLong(9)
        )

        return post
    }

    override suspend fun getNextPostByDate(
        date: Long,
        sqlConnection: SqlConnection
    ): Post? {
        val query =
            "SELECT `id`, `title`, `category_id`, `writer_user_id`, `text`, `date`, `move_date`, `status`, `image`, `views` FROM `${getTablePrefix() + tableName}` WHERE (`id`,`date`) IN (SELECT `id`, MIN(`date`) FROM `${getTablePrefix() + tableName}` where `status` = ? and `date` > ? GROUP BY `id` ) order by `date` limit 1"

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

        val post = Post(
            row.getInteger(0),
            row.getString(1),
            row.getInteger(2),
            row.getInteger(3),
            row.getBuffer(4).toString(),
            row.getLong(5),
            row.getLong(6),
            row.getInteger(7),
            row.getBuffer(8).toString(),
            row.getLong(9)
        )

        return post
    }
}