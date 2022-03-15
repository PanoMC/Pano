package com.panomc.platform.db.implementation

import com.panomc.platform.db.DaoImpl
import com.panomc.platform.db.dao.PanelNotificationDao
import com.panomc.platform.db.model.PanelNotification
import com.panomc.platform.model.Result
import com.panomc.platform.model.Successful
import com.panomc.platform.util.NotificationStatus
import io.vertx.core.AsyncResult
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.RowSet
import io.vertx.sqlclient.SqlConnection
import io.vertx.sqlclient.Tuple

class PanelNotificationDaoImpl(override val tableName: String = "panel_notification") : DaoImpl(),
    PanelNotificationDao {

    override fun init(): (sqlConnection: SqlConnection, handler: (asyncResult: AsyncResult<*>) -> Unit) -> Unit =
        { sqlConnection, handler ->
            sqlConnection
                .query(
                    """
                            CREATE TABLE IF NOT EXISTS `${getTablePrefix() + tableName}` (
                              `id` int NOT NULL AUTO_INCREMENT,
                              `user_id` int NOT NULL,
                              `type_ID` varchar(255) NOT NULL,
                              `date` BIGINT(20) NOT NULL,
                              `status` varchar(255) NOT NULL,
                              PRIMARY KEY (`id`)
                            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Panel Notification table.';
                        """
                )
                .execute {
                    handler.invoke(it)
                }
        }

    override fun add(
        panelNotification: PanelNotification,
        sqlConnection: SqlConnection,
        handler: (result: Result?, asyncResult: AsyncResult<*>) -> Unit
    ) {
        val query =
            "INSERT INTO `${getTablePrefix() + tableName}` (user_id, type_ID, date, status) " +
                    "VALUES (?, ?, ?, ?)"

        sqlConnection
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    panelNotification.userID,
                    panelNotification.typeID,
                    panelNotification.date,
                    panelNotification.status
                )
            ) { queryResult ->
                if (queryResult.succeeded())
                    handler.invoke(Successful(), queryResult)
                else
                    handler.invoke(null, queryResult)
            }
    }

    override fun getCountOfNotReadByUserID(
        userID: Int,
        sqlConnection: SqlConnection,
        handler: (count: Int?, asyncResult: AsyncResult<*>) -> Unit
    ) {
        val query =
            "SELECT count(`id`) FROM `${getTablePrefix() + tableName}` WHERE `user_id` = ? AND `status` = ? ORDER BY `date` DESC, `id` DESC"

        sqlConnection
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    userID,
                    NotificationStatus.NOT_READ,
                )
            ) { queryResult ->
                if (queryResult.succeeded()) {
                    val rows: RowSet<Row> = queryResult.result()

                    handler.invoke(rows.toList()[0].getInteger(0), queryResult)
                } else
                    handler.invoke(null, queryResult)
            }
    }

    override fun getCountByUserID(
        userID: Int,
        sqlConnection: SqlConnection,
        handler: (count: Int?, asyncResult: AsyncResult<*>) -> Unit
    ) {
        val query =
            "SELECT count(`id`) FROM `${getTablePrefix() + tableName}` WHERE `user_id` = ? ORDER BY `date` DESC, `id` DESC"

        sqlConnection
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    userID
                )
            ) { queryResult ->
                if (queryResult.succeeded()) {
                    val rows: RowSet<Row> = queryResult.result()

                    handler.invoke(rows.toList()[0].getInteger(0), queryResult)
                } else
                    handler.invoke(null, queryResult)
            }
    }

    override fun getLast10ByUserID(
        userID: Int,
        sqlConnection: SqlConnection,
        handler: (notifications: List<PanelNotification>?, asyncResult: AsyncResult<*>) -> Unit
    ) {
        val query =
            "SELECT `id`, `user_id`, `type_ID`, `date`, `status` FROM `${getTablePrefix() + tableName}` WHERE `user_id` = ? ORDER BY `date` DESC, `id` DESC LIMIT 10"

        sqlConnection
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    userID
                )
            ) { queryResult ->
                if (queryResult.succeeded()) {
                    val rows: RowSet<Row> = queryResult.result()
                    val notifications = mutableListOf<PanelNotification>()

                    if (rows.size() > 0)
                        rows.forEach { row ->
                            notifications.add(
                                PanelNotification(
                                    row.getInteger(0),
                                    row.getInteger(1),
                                    row.getString(2),
                                    row.getLong(3),
                                    NotificationStatus.valueOf(row.getString(4))
                                )
                            )
                        }

                    handler.invoke(notifications, queryResult)
                } else
                    handler.invoke(null, queryResult)
            }
    }

    override fun get10ByUserIDAndStartFromID(
        userID: Int,
        notificationID: Int,
        sqlConnection: SqlConnection,
        handler: (notifications: List<PanelNotification>?, asyncResult: AsyncResult<*>) -> Unit
    ) {
        val query =
            "SELECT `id`, `user_id`, `type_ID`, `date`, `status` FROM `${getTablePrefix() + tableName}` WHERE `user_id` = ? AND id < ? ORDER BY `date` DESC, `id` DESC LIMIT 10"

        sqlConnection
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    userID,
                    notificationID
                )
            ) { queryResult ->
                if (queryResult.succeeded()) {
                    val rows: RowSet<Row> = queryResult.result()
                    val notifications = mutableListOf<PanelNotification>()

                    if (rows.size() > 0)
                        rows.forEach { row ->
                            notifications.add(
                                PanelNotification(
                                    row.getInteger(0),
                                    row.getInteger(1),
                                    row.getString(2),
                                    row.getLong(3),
                                    NotificationStatus.valueOf(row.getString(4))
                                )
                            )
                        }

                    handler.invoke(notifications, queryResult)
                } else
                    handler.invoke(null, queryResult)
            }
    }

    override fun markReadLast10(
        userID: Int,
        sqlConnection: SqlConnection,
        handler: (result: Result?, asyncResult: AsyncResult<*>) -> Unit
    ) {
        val query =
            "UPDATE `${getTablePrefix() + tableName}` SET status = ? WHERE `user_id` = ? ORDER BY `date` DESC, `id` DESC LIMIT 10"

        sqlConnection
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    NotificationStatus.READ,
                    userID
                )
            ) { queryResult ->
                if (queryResult.succeeded())
                    handler.invoke(Successful(), queryResult)
                else
                    handler.invoke(null, queryResult)
            }
    }

    override fun markReadLast10StartFromID(
        userID: Int,
        notificationID: Int,
        sqlConnection: SqlConnection,
        handler: (result: Result?, asyncResult: AsyncResult<*>) -> Unit
    ) {
        val query =
            "UPDATE `${getTablePrefix() + tableName}` SET status = ? WHERE `user_id` = ? AND id < ?  ORDER BY `date` DESC, `id` DESC LIMIT 10"

        sqlConnection
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    NotificationStatus.READ,
                    userID,
                    notificationID
                )
            ) { queryResult ->
                if (queryResult.succeeded())
                    handler.invoke(Successful(), queryResult)
                else
                    handler.invoke(null, queryResult)
            }
    }

    override fun getLast5ByUserID(
        userID: Int,
        sqlConnection: SqlConnection,
        handler: (notifications: List<PanelNotification>?, asyncResult: AsyncResult<*>) -> Unit
    ) {
        val query =
            "SELECT `id`, `user_id`, `type_ID`, `date`, `status` FROM `${getTablePrefix() + tableName}` WHERE `user_id` = ? ORDER BY `date` DESC, `id` DESC LIMIT 5"

        sqlConnection
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    userID
                )
            ) { queryResult ->
                if (queryResult.succeeded()) {
                    val rows: RowSet<Row> = queryResult.result()

                    val notifications = mutableListOf<PanelNotification>()

                    if (rows.size() > 0)
                        rows.forEach { row ->
                            notifications.add(
                                PanelNotification(
                                    row.getInteger(0),
                                    row.getInteger(1),
                                    row.getString(2),
                                    row.getLong(3),
                                    NotificationStatus.valueOf(row.getString(4))
                                )
                            )
                        }

                    handler.invoke(notifications, queryResult)
                } else
                    handler.invoke(null, queryResult)
            }
    }

    override fun markReadLat5ByUserID(
        userID: Int,
        sqlConnection: SqlConnection,
        handler: (result: Result?, asyncResult: AsyncResult<*>) -> Unit
    ) {
        val query =
            "UPDATE `${getTablePrefix() + tableName}` SET status = ? WHERE `user_id` = ? ORDER BY `date` DESC, `id` DESC LIMIT 5"

        sqlConnection
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    NotificationStatus.READ,
                    userID
                )
            ) { queryResult ->
                if (queryResult.succeeded())
                    handler.invoke(Successful(), queryResult)
                else
                    handler.invoke(null, queryResult)
            }
    }

    override fun existsByID(
        id: Int,
        sqlConnection: SqlConnection,
        handler: (exists: Boolean?, asyncResult: AsyncResult<*>) -> Unit
    ) {
        val query = "SELECT COUNT(id) FROM `${getTablePrefix() + tableName}` where `id` = ?"

        sqlConnection
            .preparedQuery(query)
            .execute(Tuple.of(id)) { queryResult ->
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
        handler: (notification: PanelNotification?, asyncResult: AsyncResult<*>) -> Unit
    ) {
        val query =
            "SELECT `id`, `user_id`, `type_ID`, `date`, `status` FROM `${getTablePrefix() + tableName}` WHERE `id` = ? ORDER BY `date` DESC, `id` DESC LIMIT 5"

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

                    val notification = PanelNotification(
                        row.getInteger(0),
                        row.getInteger(1),
                        row.getString(2),
                        row.getLong(3),
                        NotificationStatus.valueOf(row.getString(4))
                    )

                    handler.invoke(notification, queryResult)
                } else
                    handler.invoke(null, queryResult)
            }
    }

    override fun deleteByID(
        id: Int,
        sqlConnection: SqlConnection,
        handler: (result: Result?, asyncResult: AsyncResult<*>) -> Unit
    ) {
        val query =
            "DELETE FROM `${getTablePrefix() + tableName}` WHERE `id` = ?"

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

    override fun deleteAllByUserID(
        userID: Int,
        sqlConnection: SqlConnection,
        handler: (result: Result?, asyncResult: AsyncResult<*>) -> Unit
    ) {
        val query =
            "DELETE FROM `${getTablePrefix() + tableName}` WHERE `user_id` = ?"

        sqlConnection
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    userID
                )
            ) { queryResult ->
                if (queryResult.succeeded())
                    handler.invoke(Successful(), queryResult)
                else
                    handler.invoke(null, queryResult)
            }
    }
}