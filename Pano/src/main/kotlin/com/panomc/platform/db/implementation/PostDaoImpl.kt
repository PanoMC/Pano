package com.panomc.platform.db.implementation

import com.panomc.platform.annotation.Dao
import com.panomc.platform.db.dao.PostDao
import com.panomc.platform.db.model.Post
import com.panomc.platform.util.PostStatus
import io.vertx.kotlin.coroutines.await
import io.vertx.mysqlclient.MySQLClient
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.RowSet
import io.vertx.sqlclient.SqlClient
import io.vertx.sqlclient.Tuple

@Dao
class PostDaoImpl : PostDao() {

    override suspend fun init(sqlClient: SqlClient) {
        sqlClient
            .query(
                """
                            CREATE TABLE IF NOT EXISTS `${getTablePrefix() + tableName}` (
                              `id` bigint NOT NULL AUTO_INCREMENT,
                              `title` MEDIUMTEXT NOT NULL,
                              `categoryId` bigint NOT NULL,
                              `writerUserId` bigint NOT NULL,
                              `text` LONGTEXT NOT NULL,
                              `date` BIGINT(20) NOT NULL,
                              `moveDate` BIGINT(20) NOT NULL,
                              `status` VARCHAR(255) NOT NULL,
                              `thumbnailUrl` mediumtext NOT NULL,
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
        sqlClient: SqlClient
    ) {
        val query = "UPDATE `${getTablePrefix() + tableName}` SET categoryId = ? WHERE categoryId = ?"

        sqlClient
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    -1,
                    categoryId
                )
            )
            .await()
    }

    override suspend fun existsById(
        id: Long,
        sqlClient: SqlClient
    ): Boolean {
        val query = "SELECT COUNT(id) FROM `${getTablePrefix() + tableName}` where `id` = ?"

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    id
                )
            )
            .await()

        return rows.toList()[0].getLong(0) == 1L
    }

    override suspend fun existsByUrl(url: String, sqlClient: SqlClient): Boolean {
        val query = "SELECT COUNT(id) FROM `${getTablePrefix() + tableName}` where `url` = ?"

        val rows: RowSet<Row> = sqlClient
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
        sqlClient: SqlClient
    ): Post? {
        val query =
            "SELECT `id`, `title`, `categoryId`, `writerUserId`, `text`, `date`, `moveDate`, `status`, `thumbnailUrl`, `views`, `url` FROM `${getTablePrefix() + tableName}` WHERE  `id` = ?"

        val rows: RowSet<Row> = sqlClient
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

        return row.toEntity()
    }

    override suspend fun getByUrl(url: String, sqlClient: SqlClient): Post? {
        val query =
            "SELECT `id`, `title`, `categoryId`, `writerUserId`, `text`, `date`, `moveDate`, `status`, `thumbnailUrl`, `views`, `url` FROM `${getTablePrefix() + tableName}` WHERE  `url` = ?"

        val rows: RowSet<Row> = sqlClient
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

        return row.toEntity()
    }

    override suspend fun moveTrashById(
        id: Long,
        sqlClient: SqlClient
    ) {
        val query =
            "UPDATE `${getTablePrefix() + tableName}` SET moveDate = ?, status = ? WHERE `id` = ?"

        sqlClient
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    System.currentTimeMillis(),
                    PostStatus.TRASH.name,
                    id
                )
            )
            .await()
    }

    override suspend fun moveDraftById(
        id: Long,
        sqlClient: SqlClient
    ) {
        val query =
            "UPDATE `${getTablePrefix() + tableName}` SET moveDate = ?, status = ? WHERE `id` = ?"

        sqlClient
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    System.currentTimeMillis(),
                    PostStatus.DRAFT.name,
                    id
                )
            )
            .await()
    }

    override suspend fun publishById(
        id: Long,
        userId: Long,
        sqlClient: SqlClient
    ) {
        val query =
            "UPDATE `${getTablePrefix() + tableName}` SET writerUserId = ?, `date` = ?, moveDate = ?, status = ? WHERE `id` = ?"

        sqlClient
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    userId,
                    System.currentTimeMillis(),
                    System.currentTimeMillis(),
                    PostStatus.PUBLISHED.name,
                    id
                )
            )
            .await()
    }

    override suspend fun insert(post: Post, sqlClient: SqlClient): Long {
        val query =
            "INSERT INTO `${getTablePrefix() + tableName}` (`title`, `categoryId`, `writerUserId`, `text`, `date`, `moveDate`, `status`, `thumbnailUrl`, `views`, `url`) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    post.title,
                    post.categoryId,
                    post.writerUserId,
                    post.text,
                    post.date,
                    post.moveDate,
                    post.status.name,
                    post.thumbnailUrl,
                    post.views,
                    post.url
                )
            )
            .await()

        return rows.property(MySQLClient.LAST_INSERTED_ID)
    }

    override suspend fun update(userId: Long, post: Post, sqlClient: SqlClient) {
        val query =
            "UPDATE `${getTablePrefix() + tableName}` SET title = ?, categoryId = ?, writerUserId = ?, text = ?, `date` = ?, moveDate = ?, status = ?, thumbnailUrl = ?, `url` = ? WHERE `id` = ?"

        sqlClient
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    post.title,
                    post.categoryId,
                    post.writerUserId,
                    post.text,
                    System.currentTimeMillis(),
                    System.currentTimeMillis(),
                    post.status.name,
                    post.thumbnailUrl,
                    post.url,
                    post.id
                )
            )
            .await()
    }

    override suspend fun updatePostUrlByUrl(url: String, newUrl: String, sqlClient: SqlClient) {
        val query =
            "UPDATE `${getTablePrefix() + tableName}` SET `url` = ? WHERE `url` = ?"

        sqlClient
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    newUrl,
                    url
                )
            )
            .await()
    }

    override suspend fun count(sqlClient: SqlClient): Long {
        val query = "SELECT COUNT(id) FROM `${getTablePrefix() + tableName}`"

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute()
            .await()

        return rows.toList()[0].getLong(0)
    }

    override suspend fun countByCategory(
        id: Long,
        sqlClient: SqlClient
    ): Long {
        val query = "SELECT COUNT(id) FROM `${getTablePrefix() + tableName}` where categoryId = ?"

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute(
                Tuple.of(id)
            )
            .await()

        return rows.toList()[0].getLong(0)
    }

    override suspend fun countByPageType(
        postStatus: PostStatus,
        sqlClient: SqlClient
    ): Long {
        val query =
            "SELECT COUNT(id) FROM `${getTablePrefix() + tableName}` WHERE `status` = ?"

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute(
                Tuple.of(postStatus.name)
            )
            .await()

        return rows.toList()[0].getLong(0)
    }

    override suspend fun countByPageTypeAndCategoryId(
        postStatus: PostStatus,
        categoryId: Long,
        sqlClient: SqlClient
    ): Long {
        val query =
            "SELECT COUNT(id) FROM `${getTablePrefix() + tableName}` WHERE `status` = ? and `categoryId` = ?"

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute(
                Tuple.of(postStatus.name, categoryId)
            )
            .await()

        return rows.toList()[0].getLong(0)
    }

    override suspend fun delete(
        id: Long,
        sqlClient: SqlClient
    ) {
        val query =
            "DELETE from `${getTablePrefix() + tableName}` WHERE `id` = ?"

        sqlClient
            .preparedQuery(query)
            .execute(
                Tuple.of(id)
            )
            .await()
    }

    override suspend fun getByCategory(
        id: Long,
        sqlClient: SqlClient
    ): List<Post> {
        val query =
            "SELECT id, title, categoryId, writerUserId, text, `date`, moveDate, status, thumbnailUrl, views, `url` FROM `${getTablePrefix() + tableName}` WHERE categoryId = ? ORDER BY `date` DESC LIMIT 5"

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute(
                Tuple.of(id)
            )
            .await()

        val posts = mutableListOf<Post>()

        posts.addAll(rows.toEntities())

        return posts
    }

    override suspend fun getByPageAndPageType(
        page: Long,
        postStatus: PostStatus,
        sqlClient: SqlClient
    ): List<Post> {
        val query =
            "SELECT `id`, `title`, `categoryId`, `writerUserId`, `text`, `date`, `moveDate`, `status`, `thumbnailUrl`, `views`, `url` FROM `${getTablePrefix() + tableName}` WHERE `status` = ? ORDER BY ${if (postStatus == PostStatus.PUBLISHED) "`date` DESC" else "moveDate DESC"} LIMIT 10 OFFSET ${(page - 1) * 10}"

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute(
                Tuple.of(postStatus.name)
            )
            .await()

        val posts = mutableListOf<Post>()

        posts.addAll(rows.toEntities())

        return posts
    }

    override suspend fun getByPagePageTypeAndCategoryId(
        page: Long,
        postStatus: PostStatus,
        categoryId: Long,
        sqlClient: SqlClient
    ): List<Post> {
        val query =
            "SELECT `id`, `title`, `categoryId`, `writerUserId`, `text`, `date`, `moveDate`, `status`, `thumbnailUrl`, `views`, `url` FROM `${getTablePrefix() + tableName}` WHERE `status` = ? and `categoryId` = ? ORDER BY ${if (postStatus == PostStatus.PUBLISHED) "`date` DESC" else "moveDate DESC"} LIMIT 10 OFFSET ${(page - 1) * 10}"

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute(
                Tuple.of(postStatus.name, categoryId)
            )
            .await()

        return rows.toEntities()
    }

    override suspend fun countOfPublished(
        sqlClient: SqlClient
    ): Long {
        val query =
            "SELECT COUNT(id) FROM `${getTablePrefix() + tableName}` WHERE status = ?"

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute(
                Tuple.of(PostStatus.PUBLISHED.name)
            )
            .await()

        return rows.toList()[0].getLong(0)
    }

    override suspend fun countOfPublishedByCategoryId(
        categoryId: Long,
        sqlClient: SqlClient
    ): Long {
        val query =
            "SELECT COUNT(`id`) FROM `${getTablePrefix() + tableName}` WHERE `status` = ? AND `categoryId` = ?"

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    PostStatus.PUBLISHED.name,
                    categoryId
                )
            )
            .await()

        return rows.toList()[0].getLong(0)
    }

    override suspend fun getPublishedListByPage(
        page: Long,
        sqlClient: SqlClient
    ): List<Post> {
        val query =
            "SELECT `id`, `title`, `categoryId`, `writerUserId`, `text`, `date`, `moveDate`, `status`, `thumbnailUrl`, `views`, `url` FROM `${getTablePrefix() + tableName}` WHERE `status` = ? ORDER BY `date` DESC LIMIT 5 OFFSET ${(page - 1) * 5}"

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute(
                Tuple.of(PostStatus.PUBLISHED.name)
            )
            .await()

        return rows.toEntities()
    }

    override suspend fun getPublishedListByPageAndCategoryId(
        categoryId: Long,
        page: Long,
        sqlClient: SqlClient
    ): List<Post> {
        val query =
            "SELECT `id`, `title`, `categoryId`, `writerUserId`, `text`, `date`, `moveDate`, `status`, `thumbnailUrl`, `views`, `url` FROM `${getTablePrefix() + tableName}` WHERE `status` = ? AND `categoryId` = ? ORDER BY `date` DESC LIMIT 5 OFFSET ${(page - 1) * 5}"

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    PostStatus.PUBLISHED.name,
                    categoryId
                )
            )
            .await()

        return rows.toEntities()
    }

    override suspend fun getListByPageAndCategoryId(
        categoryId: Long,
        page: Long,
        sqlClient: SqlClient
    ): List<Post> {
        val query =
            "SELECT `id`, `title`, `categoryId`, `writerUserId`, `text`, `date`, `moveDate`, `status`, `thumbnailUrl`, `views`, `url` FROM `${getTablePrefix() + tableName}` WHERE `categoryId` = ? ORDER BY `date` DESC LIMIT 10 OFFSET ${(page - 1) * 10}"

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    categoryId
                )
            )
            .await()

        return rows.toEntities()
    }

    override suspend fun increaseViewByOne(
        id: Long,
        sqlClient: SqlClient
    ) {
        val query =
            "UPDATE `${getTablePrefix() + tableName}` SET `views` = `views` + 1 WHERE `id` = ?"

        sqlClient
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    id
                )
            )
            .await()
    }

    override suspend fun increaseViewByOne(url: String, sqlClient: SqlClient) {
        val query =
            "UPDATE `${getTablePrefix() + tableName}` SET `views` = `views` + 1 WHERE `url` = ?"

        sqlClient
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
        sqlClient: SqlClient
    ): Boolean {
        val query =
            "SELECT count(`id`) FROM `${getTablePrefix() + tableName}` WHERE (`id`,`date`) IN ( SELECT `id`, max(`date`) FROM `${getTablePrefix() + tableName}` where `status`= ? and `date` < ? GROUP BY `id`) order by `date` DESC limit 1"

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    PostStatus.PUBLISHED.name,
                    date
                )
            )
            .await()

        return rows.toList()[0].getLong(0) > 0L
    }

    override suspend fun isNextPostExistsByDate(
        date: Long,
        sqlClient: SqlClient
    ): Boolean {
        val query =
            "SELECT count(`id`) FROM `${getTablePrefix() + tableName}` WHERE (`id`,`date`) IN ( SELECT `id`, MIN(`date`) FROM `${getTablePrefix() + tableName}`where `status`= ? and `date` > ? GROUP BY `id`) order by `date` limit 1"

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    PostStatus.PUBLISHED.name,
                    date
                )
            )
            .await()

        return rows.toList()[0].getLong(0) > 0L
    }

    override suspend fun getPreviousPostByDate(
        date: Long,
        sqlClient: SqlClient
    ): Post? {
        val query =
            "SELECT `id`, `title`, `categoryId`, `writerUserId`, `text`, `date`, `moveDate`, `status`, `thumbnailUrl`, `views`, `url` FROM `${getTablePrefix() + tableName}` WHERE (`id`,`date`) IN ( SELECT `id`, max(`date`) FROM `${getTablePrefix() + tableName}` where `status` = ? and `date` < ? GROUP BY `id` ) order by `date` DESC limit 1"

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    PostStatus.PUBLISHED.name,
                    date
                )
            )
            .await()

        if (rows.size() == 0) {
            return null
        }

        val row = rows.toList()[0]

        return row.toEntity()
    }

    override suspend fun getNextPostByDate(
        date: Long,
        sqlClient: SqlClient
    ): Post? {
        val query =
            "SELECT `id`, `title`, `categoryId`, `writerUserId`, `text`, `date`, `moveDate`, `status`, `thumbnailUrl`, `views`, `url` FROM `${getTablePrefix() + tableName}` WHERE (`id`,`date`) IN (SELECT `id`, MIN(`date`) FROM `${getTablePrefix() + tableName}` where `status` = ? and `date` > ? GROUP BY `id` ) order by `date` limit 1"

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    PostStatus.PUBLISHED.name,
                    date
                )
            )
            .await()

        if (rows.size() == 0) {
            return null
        }

        val row = rows.toList()[0]

        return row.toEntity()
    }

    override suspend fun updateUserIdByUserId(userId: Long, newUserId: Long, sqlClient: SqlClient) {
        val query = "UPDATE `${getTablePrefix() + tableName}` SET `writerUserId` = ? WHERE `writerUserId` = ?"

        sqlClient
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    newUserId,
                    userId
                )
            )
            .await()
    }
}