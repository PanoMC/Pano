package com.panomc.platform.db.implementation

import com.panomc.platform.annotation.Dao
import com.panomc.platform.db.DaoImpl
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.dao.NotificationDao
import com.panomc.platform.db.model.Notification
import com.panomc.platform.notification.NotificationStatus
import io.vertx.kotlin.coroutines.await
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.RowSet
import io.vertx.sqlclient.SqlClient
import io.vertx.sqlclient.Tuple

@Dao
class NotificationDaoImpl(databaseManager: DatabaseManager) : DaoImpl(databaseManager, "notification"),
    NotificationDao {

    override suspend fun init(sqlClient: SqlClient) {
        sqlClient
            .query(
                """
                            CREATE TABLE IF NOT EXISTS `${getTablePrefix() + tableName}` (
                              `id` bigint NOT NULL AUTO_INCREMENT,
                              `user_id` bigint NOT NULL,
                              `type` varchar(255) NOT NULL,
                              `properties` mediumtext NOT NULL,
                              `date` BIGINT(20) NOT NULL,
                              `status` varchar(255) NOT NULL,
                              PRIMARY KEY (`id`)
                            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Notification table.';
                        """
            )
            .execute()
            .await()
    }

    override suspend fun add(
        notification: Notification,
        sqlClient: SqlClient
    ) {
        val query =
            "INSERT INTO `${getTablePrefix() + tableName}` (`user_id`, `type`, `properties`, `date`, `status`) " +
                    "VALUES (?, ?, ?, ?, ?)"

        sqlClient
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    notification.userId,
                    notification.type,
                    notification.properties.toString(),
                    notification.date,
                    notification.status
                )
            ).await()
    }

    override suspend fun addAll(notifications: List<Notification>, sqlClient: SqlClient) {
        val tuple = Tuple.tuple()
        var listText = ""

        notifications.forEach { notification ->
            tuple.addValue(notification.userId)
            tuple.addValue(notification.type)
            tuple.addValue(notification.properties.toString())
            tuple.addValue(notification.date)
            tuple.addValue(notification.status)

            if (listText == "")
                listText = "(?, ?, ?, ?, ?)"
            else
                listText += ", (?, ?, ?, ?, ?)"
        }

        val query =
            "INSERT INTO `${getTablePrefix() + tableName}` (`user_id`, `type`, `properties`, `date`, `status`) " +
                    "VALUES $listText"

        sqlClient
            .preparedQuery(query)
            .execute(tuple)
            .await()
    }

    override suspend fun getCountOfNotReadByUserId(
        userId: Long,
        sqlClient: SqlClient
    ): Long {
        val query =
            "SELECT count(`id`) FROM `${getTablePrefix() + tableName}` WHERE `user_id` = ? AND `status` = ? ORDER BY `date` DESC, `id` DESC"

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    userId,
                    NotificationStatus.NOT_READ,
                )
            ).await()

        return rows.toList()[0].getLong(0)
    }

    override suspend fun getCountByUserId(
        userId: Long,
        sqlClient: SqlClient
    ): Long {
        val query =
            "SELECT count(`id`) FROM `${getTablePrefix() + tableName}` WHERE `user_id` = ? ORDER BY `date` DESC, `id` DESC"

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    userId
                )
            ).await()

        return rows.toList()[0].getLong(0)
    }

    override suspend fun getLast10ByUserId(
        userId: Long,
        sqlClient: SqlClient
    ): List<Notification> {
        val query =
            "SELECT `id`, `user_id`, `type`, `properties`, `date`, `status` FROM `${getTablePrefix() + tableName}` WHERE `user_id` = ? ORDER BY `date` DESC, `id` DESC LIMIT 10"

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    userId
                )
            ).await()

        return Notification.from(rows)
    }

    override suspend fun get10ByUserIdAndStartFromId(
        userId: Long,
        notificationId: Long,
        sqlClient: SqlClient
    ): List<Notification> {
        val query =
            "SELECT `id`, `user_id`, `type`, `properties`, `date`, `status` FROM `${getTablePrefix() + tableName}` WHERE `user_id` = ? AND id < ? ORDER BY `date` DESC, `id` DESC LIMIT 10"

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    userId,
                    notificationId
                )
            ).await()

        return Notification.from(rows)
    }

    override suspend fun markReadLast10(
        userId: Long,
        sqlClient: SqlClient
    ) {
        val query =
            "UPDATE `${getTablePrefix() + tableName}` SET status = ? WHERE `user_id` = ? ORDER BY `date` DESC, `id` DESC LIMIT 10"

        sqlClient
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    NotificationStatus.READ,
                    userId
                )
            ).await()
    }

    override suspend fun markReadLast10StartFromId(
        userId: Long,
        notificationId: Long,
        sqlClient: SqlClient
    ) {
        val query =
            "UPDATE `${getTablePrefix() + tableName}` SET status = ? WHERE `user_id` = ? AND id < ?  ORDER BY `date` DESC, `id` DESC LIMIT 10"

        sqlClient
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    NotificationStatus.READ,
                    userId,
                    notificationId
                )
            ).await()
    }

    override suspend fun getLast5ByUserId(
        userId: Long,
        sqlClient: SqlClient
    ): List<Notification> {
        val query =
            "SELECT `id`, `user_id`, `type`, `properties`, `date`, `status` FROM `${getTablePrefix() + tableName}` WHERE `user_id` = ? ORDER BY `date` DESC, `id` DESC LIMIT 5"

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    userId
                )
            ).await()

        return Notification.from(rows)
    }

    override suspend fun markReadLast5ByUserId(
        userId: Long,
        sqlClient: SqlClient
    ) {
        val query =
            "UPDATE `${getTablePrefix() + tableName}` SET status = ? WHERE `user_id` = ? ORDER BY `date` DESC, `id` DESC LIMIT 5"

        sqlClient
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    NotificationStatus.READ,
                    userId
                )
            ).await()
    }

    override suspend fun existsById(
        id: Long,
        sqlClient: SqlClient
    ): Boolean {
        val query = "SELECT COUNT(id) FROM `${getTablePrefix() + tableName}` where `id` = ?"

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute(Tuple.of(id))
            .await()

        return rows.toList()[0].getLong(0) == 1L
    }

    override suspend fun getById(
        id: Long,
        sqlClient: SqlClient
    ): Notification? {
        val query =
            "SELECT `id`, `user_id`, `type`, `properties`, `date`, `status` FROM `${getTablePrefix() + tableName}` WHERE `id` = ? ORDER BY `date` DESC, `id` DESC LIMIT 5"

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    id
                )
            ).await()

        if (rows.size() == 0) {
            return null
        }

        val row = rows.toList()[0]

        return Notification.from(row)
    }

    override suspend fun deleteById(
        id: Long,
        sqlClient: SqlClient
    ) {
        val query =
            "DELETE FROM `${getTablePrefix() + tableName}` WHERE `id` = ?"

        sqlClient
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    id
                )
            ).await()
    }

    override suspend fun markReadById(id: Long, sqlClient: SqlClient) {
        val query =
            "UPDATE `${getTablePrefix() + tableName}` SET `status` = ? WHERE `id` = ?"

        sqlClient
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    NotificationStatus.READ,
                    id
                )
            ).await()
    }

    override suspend fun deleteAllByUserId(
        userId: Long,
        sqlClient: SqlClient
    ) {
        val query =
            "DELETE FROM `${getTablePrefix() + tableName}` WHERE `user_id` = ?"

        sqlClient
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    userId
                )
            )
            .await()
    }
}