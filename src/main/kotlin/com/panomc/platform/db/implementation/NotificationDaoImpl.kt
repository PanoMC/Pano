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
import io.vertx.sqlclient.SqlConnection
import io.vertx.sqlclient.Tuple

@Dao
class NotificationDaoImpl(databaseManager: DatabaseManager) : DaoImpl(databaseManager, "notification"),
    NotificationDao {

    override suspend fun init(sqlConnection: SqlConnection) {
        sqlConnection
            .query(
                """
                            CREATE TABLE IF NOT EXISTS `${getTablePrefix() + tableName}` (
                              `id` bigint NOT NULL AUTO_INCREMENT,
                              `user_id` bigint NOT NULL,
                              `type_id` varchar(255) NOT NULL,
                              `action` varchar(255) NOT NULL,
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
        sqlConnection: SqlConnection
    ) {
        val query =
            "INSERT INTO `${getTablePrefix() + tableName}` (`user_id`, `type_id`, `action`, `properties`, `date`, `status`) " +
                    "VALUES (?, ?, ?, ?, ?, ?)"

        sqlConnection
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    notification.userId,
                    notification.typeId,
                    notification.action,
                    notification.properties.toString(),
                    notification.date,
                    notification.status
                )
            ).await()
    }

    override suspend fun addAll(notifications: List<Notification>, sqlConnection: SqlConnection) {
        val tuple = Tuple.tuple()
        var listText = ""

        notifications.forEach { notification ->
            tuple.addValue(notification.userId)
            tuple.addValue(notification.typeId)
            tuple.addValue(notification.action)
            tuple.addValue(notification.properties.toString())
            tuple.addValue(notification.date)
            tuple.addValue(notification.status)

            if (listText == "")
                listText = "(?, ?, ?, ?, ?, ?)"
            else
                listText += ", (?, ?, ?, ?, ?, ?)"
        }

        val query =
            "INSERT INTO `${getTablePrefix() + tableName}` (`user_id`, `type_id`, `action`, `properties`, `date`, `status`) " +
                    "VALUES $listText"

        sqlConnection
            .preparedQuery(query)
            .execute(tuple)
            .await()
    }

    override suspend fun getCountOfNotReadByUserId(
        userId: Long,
        sqlConnection: SqlConnection
    ): Long {
        val query =
            "SELECT count(`id`) FROM `${getTablePrefix() + tableName}` WHERE `user_id` = ? AND `status` = ? ORDER BY `date` DESC, `id` DESC"

        val rows: RowSet<Row> = sqlConnection
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
        sqlConnection: SqlConnection
    ): Long {
        val query =
            "SELECT count(`id`) FROM `${getTablePrefix() + tableName}` WHERE `user_id` = ? ORDER BY `date` DESC, `id` DESC"

        val rows: RowSet<Row> = sqlConnection
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
        sqlConnection: SqlConnection
    ): List<Notification> {
        val query =
            "SELECT `id`, `user_id`, `type_id`, `action`, `properties`, `date`, `status` FROM `${getTablePrefix() + tableName}` WHERE `user_id` = ? ORDER BY `date` DESC, `id` DESC LIMIT 10"

        val rows: RowSet<Row> = sqlConnection
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
        sqlConnection: SqlConnection
    ): List<Notification> {
        val query =
            "SELECT `id`, `user_id`, `type_id`, `action`, `properties`, `date`, `status` FROM `${getTablePrefix() + tableName}` WHERE `user_id` = ? AND id < ? ORDER BY `date` DESC, `id` DESC LIMIT 10"

        val rows: RowSet<Row> = sqlConnection
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
        sqlConnection: SqlConnection
    ) {
        val query =
            "UPDATE `${getTablePrefix() + tableName}` SET status = ? WHERE `user_id` = ? ORDER BY `date` DESC, `id` DESC LIMIT 10"

        sqlConnection
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
        sqlConnection: SqlConnection
    ) {
        val query =
            "UPDATE `${getTablePrefix() + tableName}` SET status = ? WHERE `user_id` = ? AND id < ?  ORDER BY `date` DESC, `id` DESC LIMIT 10"

        sqlConnection
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
        sqlConnection: SqlConnection
    ): List<Notification> {
        val query =
            "SELECT `id`, `user_id`, `type_id`, `action`, `properties`, `date`, `status` FROM `${getTablePrefix() + tableName}` WHERE `user_id` = ? ORDER BY `date` DESC, `id` DESC LIMIT 5"

        val rows: RowSet<Row> = sqlConnection
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
        sqlConnection: SqlConnection
    ) {
        val query =
            "UPDATE `${getTablePrefix() + tableName}` SET status = ? WHERE `user_id` = ? ORDER BY `date` DESC, `id` DESC LIMIT 5"

        sqlConnection
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
        sqlConnection: SqlConnection
    ): Boolean {
        val query = "SELECT COUNT(id) FROM `${getTablePrefix() + tableName}` where `id` = ?"

        val rows: RowSet<Row> = sqlConnection
            .preparedQuery(query)
            .execute(Tuple.of(id))
            .await()

        return rows.toList()[0].getLong(0) == 1L
    }

    override suspend fun getById(
        id: Long,
        sqlConnection: SqlConnection
    ): Notification? {
        val query =
            "SELECT `id`, `user_id`, `type_id`, `action`, `properties`, `date`, `status` FROM `${getTablePrefix() + tableName}` WHERE `id` = ? ORDER BY `date` DESC, `id` DESC LIMIT 5"

        val rows: RowSet<Row> = sqlConnection
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
        sqlConnection: SqlConnection
    ) {
        val query =
            "DELETE FROM `${getTablePrefix() + tableName}` WHERE `id` = ?"

        sqlConnection
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    id
                )
            ).await()
    }

    override suspend fun markReadById(id: Long, sqlConnection: SqlConnection) {
        val query =
            "UPDATE `${getTablePrefix() + tableName}` SET `status` = ? WHERE `id` = ?"

        sqlConnection
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
        sqlConnection: SqlConnection
    ) {
        val query =
            "DELETE FROM `${getTablePrefix() + tableName}` WHERE `user_id` = ?"

        sqlConnection
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    userId
                )
            )
            .await()
    }
}