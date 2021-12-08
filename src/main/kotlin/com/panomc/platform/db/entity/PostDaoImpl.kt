package com.panomc.platform.db.entity

import com.panomc.platform.db.DaoImpl
import com.panomc.platform.db.dao.PostDao
import com.panomc.platform.db.model.Post
import com.panomc.platform.model.PostStatus
import com.panomc.platform.model.Result
import com.panomc.platform.model.Successful
import io.vertx.core.AsyncResult
import io.vertx.mysqlclient.MySQLClient
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.RowSet
import io.vertx.sqlclient.SqlConnection
import io.vertx.sqlclient.Tuple

class PostDaoImpl(override val tableName: String = "post") : DaoImpl(), PostDao {

    override fun init(): (sqlConnection: SqlConnection, handler: (asyncResult: AsyncResult<*>) -> Unit) -> Unit =
        { sqlConnection, handler ->
            sqlConnection
                .query(
                    """
                            CREATE TABLE IF NOT EXISTS `${getTablePrefix() + tableName}` (
                              `id` int NOT NULL AUTO_INCREMENT,
                              `title` MEDIUMTEXT NOT NULL,
                              `category_id` int(11) NOT NULL,
                              `writer_user_id` int(11) NOT NULL,
                              `post` longblob NOT NULL,
                              `date` BIGINT(20) NOT NULL,
                              `move_date` BIGINT(20) NOT NULL,
                              `status` int(1) NOT NULL,
                              `image` longblob NOT NULL,
                              `views` BIGINT(20) NOT NULL,
                              PRIMARY KEY (`id`)
                            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Posts table.';
                        """
                )
                .execute {
                    handler.invoke(it)
                }
        }

    override fun removePostCategoriesByCategoryID(
        categoryID: Int,
        sqlConnection: SqlConnection,
        handler: (result: Result?, asyncResult: AsyncResult<*>) -> Unit
    ) {
        val query = "UPDATE `${getTablePrefix() + tableName}` SET category_id = ? WHERE category_id = ?"

        sqlConnection
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    -1,
                    categoryID
                )
            ) { queryResult ->
                if (queryResult.succeeded())
                    handler.invoke(Successful(), queryResult)
                else
                    handler.invoke(null, queryResult)
            }
    }

    override fun isExistsByID(
        id: Int,
        sqlConnection: SqlConnection,
        handler: (exists: Boolean?, asyncResult: AsyncResult<*>) -> Unit
    ) {
        val query = "SELECT COUNT(id) FROM `${getTablePrefix() + tableName}` where `id` = ?"

        sqlConnection
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    id
                )
            ) { queryResult ->
                if (queryResult.succeeded()) {
                    val rows: RowSet<Row> = queryResult.result()

                    handler.invoke(rows.toList()[0].getInteger(0) == 1, queryResult)
                } else
                    handler.invoke(null, queryResult)
            }
    }

    override fun getByID(
        id: Int,
        sqlConnection: SqlConnection,
        handler: (post: Post?, asyncResult: AsyncResult<*>) -> Unit
    ) {
        val query =
            "SELECT `id`, `title`, `category_id`, `writer_user_id`, `post`, `date`, `move_date`, `status`, `image`, `views` FROM `${getTablePrefix() + tableName}` WHERE  `id` = ?"

        sqlConnection
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    id
                )
            ) { queryResult ->
                if (queryResult.succeeded()) {
                    val rows: RowSet<Row> = queryResult.result()
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

                    handler.invoke(post, queryResult)
                } else
                    handler.invoke(null, queryResult)
            }
    }

    override fun moveTrashByID(
        id: Int,
        sqlConnection: SqlConnection,
        handler: (result: Result?, asyncResult: AsyncResult<*>) -> Unit
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
            ) { queryResult ->
                if (queryResult.succeeded())
                    handler.invoke(Successful(), queryResult)
                else
                    handler.invoke(null, queryResult)
            }
    }

    override fun moveDraftByID(
        id: Int,
        sqlConnection: SqlConnection,
        handler: (result: Result?, asyncResult: AsyncResult<*>) -> Unit
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
            ) { queryResult ->
                if (queryResult.succeeded())
                    handler.invoke(Successful(), queryResult)
                else
                    handler.invoke(null, queryResult)
            }
    }

    override fun publishByID(
        id: Int,
        userID: Int,
        sqlConnection: SqlConnection,
        handler: (result: Result?, asyncResult: AsyncResult<*>) -> Unit
    ) {
        val query =
            "UPDATE `${getTablePrefix() + tableName}` SET writer_user_id = ?, `date` = ?, move_date = ?, status = ? WHERE `id` = ?"

        sqlConnection
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    userID,
                    System.currentTimeMillis(),
                    System.currentTimeMillis(),
                    1,
                    id
                )
            ) { queryResult ->
                if (queryResult.succeeded())
                    handler.invoke(Successful(), queryResult)
                else
                    handler.invoke(null, queryResult)
            }
    }

    override fun insertAndPublish(
        post: Post,
        sqlConnection: SqlConnection,
        handler: (postID: Long?, asyncResult: AsyncResult<*>) -> Unit
    ) {
        val query =
            "INSERT INTO `${getTablePrefix() + tableName}` (`title`, `category_id`, `writer_user_id`, `post`, `date`, `move_date`, `status`, `image`, `views`) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)"

        sqlConnection
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    post.title,
                    post.categoryId,
                    post.writerUserID,
                    post.post,
                    post.date,
                    post.moveDate,
                    post.status,
                    post.image,
                    post.views
                )
            ) { queryResult ->
                if (queryResult.succeeded()) {
                    val rows: RowSet<Row> = queryResult.result()

                    handler.invoke(rows.property(MySQLClient.LAST_INSERTED_ID), queryResult)
                } else
                    handler.invoke(null, queryResult)
            }
    }

    override fun updateAndPublish(
        userID: Int,
        post: Post,
        sqlConnection: SqlConnection,
        handler: (result: Result?, asyncResult: AsyncResult<*>) -> Unit
    ) {
        val query =
            "UPDATE `${getTablePrefix() + tableName}` SET title = ?, category_id = ?, writer_user_id = ?, post = ?, `date` = ?, move_date = ?, status = ?, image = ? WHERE `id` = ?"

        sqlConnection
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    post.title,
                    post.categoryId,
                    post.writerUserID,
                    post.post,
                    System.currentTimeMillis(),
                    System.currentTimeMillis(),
                    1,
                    post.image,
                    post.id
                )
            ) { queryResult ->
                if (queryResult.succeeded())
                    handler.invoke(Successful(), queryResult)
                else
                    handler.invoke(null, queryResult)
            }
    }

    override fun count(sqlConnection: SqlConnection, handler: (count: Int?, asyncResult: AsyncResult<*>) -> Unit) {
        val query = "SELECT COUNT(id) FROM `${getTablePrefix() + tableName}`"

        sqlConnection
            .preparedQuery(query)
            .execute()
            { queryResult ->
                if (queryResult.succeeded()) {
                    val rows: RowSet<Row> = queryResult.result()

                    handler.invoke(rows.toList()[0].getInteger(0), queryResult)
                } else
                    handler.invoke(null, queryResult)
            }
    }

    override fun countByCategory(
        id: Int,
        sqlConnection: SqlConnection,
        handler: (count: Int?, asyncResult: AsyncResult<*>) -> Unit
    ) {
        val query = "SELECT COUNT(id) FROM `${getTablePrefix() + tableName}` where category_id = ?"

        sqlConnection
            .preparedQuery(query)
            .execute(
                Tuple.of(id)
            )
            { queryResult ->
                if (queryResult.succeeded()) {
                    val rows: RowSet<Row> = queryResult.result()

                    handler.invoke(rows.toList()[0].getInteger(0), queryResult)
                } else
                    handler.invoke(null, queryResult)
            }
    }

    override fun countByPageType(
        pageType: Int,
        sqlConnection: SqlConnection,
        handler: (count: Int?, asyncResult: AsyncResult<*>) -> Unit
    ) {
        val query =
            "SELECT COUNT(id) FROM `${getTablePrefix() + tableName}` WHERE status = ?"

        sqlConnection
            .preparedQuery(query)
            .execute(
                Tuple.of(pageType)
            )
            { queryResult ->
                if (queryResult.succeeded()) {
                    val rows: RowSet<Row> = queryResult.result()

                    handler.invoke(rows.toList()[0].getInteger(0), queryResult)
                } else
                    handler.invoke(null, queryResult)
            }
    }

    override fun delete(
        id: Int,
        sqlConnection: SqlConnection,
        handler: (result: Result?, asyncResult: AsyncResult<*>) -> Unit
    ) {
        val query =
            "DELETE from `${getTablePrefix() + tableName}` WHERE id = ?"

        sqlConnection
            .preparedQuery(query)
            .execute(
                Tuple.of(id)
            )
            { queryResult ->
                if (queryResult.succeeded())
                    handler.invoke(Successful(), queryResult)
                else
                    handler.invoke(null, queryResult)
            }
    }

    override fun getByCategory(
        id: Int,
        sqlConnection: SqlConnection,
        handler: (posts: List<Post>?, asyncResult: AsyncResult<*>) -> Unit
    ) {
        val query =
            "SELECT id, title, category_id, writer_user_id, post, `date`, move_date, status, image, views FROM `${getTablePrefix() + tableName}` WHERE category_id = ? ORDER BY `date` DESC LIMIT 5"

        sqlConnection
            .preparedQuery(query)
            .execute(
                Tuple.of(id)
            )
            { queryResult ->
                if (queryResult.succeeded()) {
                    val rows: RowSet<Row> = queryResult.result()
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

                    handler.invoke(posts, queryResult)
                } else
                    handler.invoke(null, queryResult)
            }
    }

    override fun getByPageAndPageType(
        page: Int,
        pageType: Int,
        sqlConnection: SqlConnection,
        handler: (posts: List<Post>?, asyncResult: AsyncResult<*>) -> Unit
    ) {
        var query =
            "SELECT `id`, `title`, `category_id`, `writer_user_id`, `post`, `date`, `move_date`, `status`, `image`, `views` FROM `${getTablePrefix() + tableName}` WHERE `status` = ? ORDER BY ${if (pageType == 1) "`date` DESC" else "move_date DESC"} LIMIT 10 OFFSET ${(page - 1) * 10}"

        sqlConnection
            .preparedQuery(query)
            .execute(
                Tuple.of(pageType)
            )
            { queryResult ->
                if (queryResult.succeeded()) {
                    val rows: RowSet<Row> = queryResult.result()
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

                    handler.invoke(posts, queryResult)
                } else
                    handler.invoke(null, queryResult)
            }
    }

    override fun countOfPublished(
        sqlConnection: SqlConnection,
        handler: (count: Int?, asyncResult: AsyncResult<*>) -> Unit
    ) {
        val query =
            "SELECT COUNT(id) FROM `${getTablePrefix() + tableName}` WHERE status = ?"

        sqlConnection
            .preparedQuery(query)
            .execute(
                Tuple.of(PostStatus.PUBLISHED.code)
            )
            { queryResult ->
                if (queryResult.succeeded()) {
                    val rows: RowSet<Row> = queryResult.result()

                    handler.invoke(rows.toList()[0].getInteger(0), queryResult)
                } else
                    handler.invoke(null, queryResult)
            }
    }

    override fun countOfPublishedByCategoryID(
        categoryID: Int,
        sqlConnection: SqlConnection,
        handler: (count: Int?, asyncResult: AsyncResult<*>) -> Unit
    ) {
        val query =
            "SELECT COUNT(`id`) FROM `${getTablePrefix() + tableName}` WHERE `status` = ? AND `category_id` = ?"

        sqlConnection
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    PostStatus.PUBLISHED.code,
                    categoryID
                )
            )
            { queryResult ->
                if (queryResult.succeeded()) {
                    val rows: RowSet<Row> = queryResult.result()

                    handler.invoke(rows.toList()[0].getInteger(0), queryResult)
                } else
                    handler.invoke(null, queryResult)
            }
    }

    override fun getPublishedListByPage(
        page: Int,
        sqlConnection: SqlConnection,
        handler: (posts: List<Post>?, asyncResult: AsyncResult<*>) -> Unit
    ) {
        val query =
            "SELECT `id`, `title`, `category_id`, `writer_user_id`, `post`, `date`, `move_date`, `status`, `image`, `views` FROM `${getTablePrefix() + tableName}` WHERE `status` = ? ORDER BY `date` DESC LIMIT 5 OFFSET ${(page - 1) * 5}"

        sqlConnection
            .preparedQuery(query)
            .execute(
                Tuple.of(PostStatus.PUBLISHED.code)
            )
            { queryResult ->
                if (queryResult.succeeded()) {
                    val rows: RowSet<Row> = queryResult.result()
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

                    handler.invoke(posts, queryResult)
                } else
                    handler.invoke(null, queryResult)
            }
    }

    override fun getPublishedListByPageAndCategoryID(
        categoryID: Int,
        page: Int,
        sqlConnection: SqlConnection,
        handler: (posts: List<Post>?, asyncResult: AsyncResult<*>) -> Unit
    ) {
        val query =
            "SELECT `id`, `title`, `category_id`, `writer_user_id`, `post`, `date`, `move_date`, `status`, `image`, `views` FROM `${getTablePrefix() + tableName}` WHERE `status` = ? AND `category_id` = ? ORDER BY `date` DESC LIMIT 5 OFFSET ${(page - 1) * 5}"

        sqlConnection
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    PostStatus.PUBLISHED.code,
                    categoryID
                )
            )
            { queryResult ->
                if (queryResult.succeeded()) {
                    val rows: RowSet<Row> = queryResult.result()
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

                    handler.invoke(posts, queryResult)
                } else
                    handler.invoke(null, queryResult)
            }
    }

    override fun getListByPageAndCategoryID(
        categoryID: Int,
        page: Int,
        sqlConnection: SqlConnection,
        handler: (posts: List<Post>?, asyncResult: AsyncResult<*>) -> Unit
    ) {
        val query =
            "SELECT `id`, `title`, `category_id`, `writer_user_id`, `post`, `date`, `move_date`, `status`, `image`, `views` FROM `${getTablePrefix() + tableName}` WHERE `category_id` = ? ORDER BY `date` DESC LIMIT 10 OFFSET ${(page - 1) * 10}"

        sqlConnection
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    categoryID
                )
            )
            { queryResult ->
                if (queryResult.succeeded()) {
                    val rows: RowSet<Row> = queryResult.result()
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

                    handler.invoke(posts, queryResult)
                } else
                    handler.invoke(null, queryResult)
            }
    }

    override fun increaseViewByOne(
        id: Int,
        sqlConnection: SqlConnection,
        handler: (result: Result?, asyncResult: AsyncResult<*>) -> Unit
    ) {
        val query =
            "UPDATE `${getTablePrefix() + tableName}` SET `views` = `views` + 1 WHERE `id` = ?"

        sqlConnection
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    id
                )
            ) { queryResult ->
                if (queryResult.succeeded())
                    handler.invoke(Successful(), queryResult)
                else
                    handler.invoke(null, queryResult)
            }
    }

    override fun isPreviousPostExistsByDate(
        date: Long,
        sqlConnection: SqlConnection,
        handler: (exists: Boolean?, asyncResult: AsyncResult<*>) -> Unit
    ) {
        val query =
            "SELECT count(`id`) FROM `${getTablePrefix() + tableName}` WHERE (`id`,`date`) IN ( SELECT `id`, max(`date`) FROM `${getTablePrefix() + tableName}` where `status`= ? and `date` < ? GROUP BY `id`) order by `date` DESC limit 1"

        sqlConnection
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    PostStatus.PUBLISHED.code,
                    date
                )
            ) { queryResult ->
                if (queryResult.succeeded()) {
                    val rows: RowSet<Row> = queryResult.result()

                    handler.invoke(rows.toList()[0].getInteger(0) > 0, queryResult)
                } else
                    handler.invoke(null, queryResult)
            }
    }

    override fun isNextPostExistsByDate(
        date: Long,
        sqlConnection: SqlConnection,
        handler: (exists: Boolean?, asyncResult: AsyncResult<*>) -> Unit
    ) {
        val query =
            "SELECT count(`id`) FROM `${getTablePrefix() + tableName}` WHERE (`id`,`date`) IN ( SELECT `id`, MIN(`date`) FROM `${getTablePrefix() + tableName}`where `status`= ? and `date` > ? GROUP BY `id`) order by `date` limit 1"

        sqlConnection
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    PostStatus.PUBLISHED.code,
                    date
                )
            ) { queryResult ->
                if (queryResult.succeeded()) {
                    val rows: RowSet<Row> = queryResult.result()

                    handler.invoke(rows.toList()[0].getInteger(0) > 0, queryResult)
                } else
                    handler.invoke(null, queryResult)
            }
    }

    override fun getPreviousPostByDate(
        date: Long,
        sqlConnection: SqlConnection,
        handler: (post: Post?, asyncResult: AsyncResult<*>) -> Unit
    ) {
        val query =
            "SELECT `id`, `title`, `category_id`, `writer_user_id`, `post`, `date`, `move_date`, `status`, `image`, `views` FROM `${getTablePrefix() + tableName}` WHERE (`id`,`date`) IN ( SELECT `id`, max(`date`) FROM `${getTablePrefix() + tableName}` where `status` = ? and `date` < ? GROUP BY `id` ) order by `date` DESC limit 1"

        sqlConnection
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    PostStatus.PUBLISHED.code,
                    date
                )
            )
            { queryResult ->
                if (queryResult.succeeded()) {
                    val rows: RowSet<Row> = queryResult.result()
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

                    handler.invoke(post, queryResult)
                } else
                    handler.invoke(null, queryResult)
            }
    }

    override fun getNextPostByDate(
        date: Long,
        sqlConnection: SqlConnection,
        handler: (post: Post?, asyncResult: AsyncResult<*>) -> Unit
    ) {
        val query =
            "SELECT `id`, `title`, `category_id`, `writer_user_id`, `post`, `date`, `move_date`, `status`, `image`, `views` FROM `${getTablePrefix() + tableName}` WHERE (`id`,`date`) IN (SELECT `id`, MIN(`date`) FROM `${getTablePrefix() + tableName}` where `status` = ? and `date` > ? GROUP BY `id` ) order by `date` limit 1"

        sqlConnection
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    PostStatus.PUBLISHED.code,
                    date
                )
            )
            { queryResult ->
                if (queryResult.succeeded()) {
                    val rows: RowSet<Row> = queryResult.result()
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

                    handler.invoke(post, queryResult)
                } else
                    handler.invoke(null, queryResult)
            }
    }
}