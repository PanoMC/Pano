package com.panomc.platform.db.implementation

import com.panomc.platform.annotation.Dao
import com.panomc.platform.db.DaoImpl
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.dao.TicketMessageDao
import com.panomc.platform.db.model.TicketMessage
import io.vertx.core.json.JsonArray
import io.vertx.kotlin.coroutines.await
import io.vertx.mysqlclient.MySQLClient
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.RowSet
import io.vertx.sqlclient.SqlConnection
import io.vertx.sqlclient.Tuple

@Dao
class TicketMessageDaoImpl(databaseManager: DatabaseManager) : DaoImpl(databaseManager, "ticket_message"),
    TicketMessageDao {
    override suspend fun init(sqlConnection: SqlConnection) {
        sqlConnection
            .query(
                """
                            CREATE TABLE IF NOT EXISTS `${getTablePrefix() + tableName}` (
                              `id` int NOT NULL AUTO_INCREMENT,
                              `user_id` int NOT NULL,
                              `ticket_id` int NOT NULL,
                              `message` text NOT NULL,
                              `date` BIGINT(20) NOT NULL,
                              `panel` int NOT NULL,
                              PRIMARY KEY (`id`)
                            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Ticket message table.';
                        """
            )
            .execute()
            .await()
    }

    override suspend fun getByTicketIdAndPage(
        ticketId: Int,
        sqlConnection: SqlConnection
    ): List<TicketMessage> {
        val query =
            "SELECT id, user_id, ticket_id, message, `date`, `panel` FROM `${getTablePrefix() + tableName}` WHERE ticket_id = ? ORDER BY id DESC LIMIT 5"

        val rows: RowSet<Row> = sqlConnection
            .preparedQuery(query)
            .execute(Tuple.of(ticketId))
            .await()

        val messages = mutableListOf<TicketMessage>()

        if (rows.size() > 0)
            rows.forEach { row ->
                messages.add(
                    TicketMessage(
                        id = row.getInteger(0),
                        userId = row.getInteger(1),
                        ticketId = row.getInteger(2),
                        message = row.getString(3),
                        date = row.getLong(4),
                        panel = row.getInteger(5)
                    )
                )
            }

        return messages
    }

    override suspend fun getByTicketIdPageAndStartFromId(
        lastMessageId: Int,
        ticketId: Int,
        sqlConnection: SqlConnection
    ): List<TicketMessage> {
        val query =
            "SELECT id, user_id, ticket_id, message, `date`, `panel` FROM `${getTablePrefix() + tableName}` WHERE ticket_id = ? and id < ? ORDER BY id DESC LIMIT 5"

        val rows: RowSet<Row> = sqlConnection
            .preparedQuery(query)
            .execute(Tuple.of(ticketId, lastMessageId))
            .await()

        val messages = mutableListOf<TicketMessage>()

        if (rows.size() > 0)
            rows.forEach { row ->
                messages.add(
                    TicketMessage(
                        id = row.getInteger(0),
                        userId = row.getInteger(1),
                        ticketId = row.getInteger(2),
                        message = row.getString(3),
                        date = row.getLong(4),
                        panel = row.getInteger(5)
                    )
                )
            }

        return messages
    }

    override suspend fun getCountByTicketId(
        ticketId: Int,
        sqlConnection: SqlConnection
    ): Int {
        val query =
            "SELECT COUNT(id) FROM `${getTablePrefix() + tableName}` where ticket_id = ?"

        val rows: RowSet<Row> = sqlConnection
            .preparedQuery(query)
            .execute(Tuple.of(ticketId))
            .await()

        return rows.toList()[0].getInteger(0)
    }

    override suspend fun addMessage(
        ticketMessage: TicketMessage,
        sqlConnection: SqlConnection
    ): Long {
        val query =
            "INSERT INTO `${getTablePrefix() + tableName}` (user_id, ticket_id, message, `date`, panel) VALUES (?, ?, ?, ?, ?)"

        val rows: RowSet<Row> = sqlConnection
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    ticketMessage.userId,
                    ticketMessage.ticketId,
                    ticketMessage.message,
                    ticketMessage.date,
                    ticketMessage.panel,
                )
            )
            .await()

        return rows.property(MySQLClient.LAST_INSERTED_ID)
    }

    override suspend fun deleteByTicketIdList(
        ticketIdList: JsonArray,
        sqlConnection: SqlConnection
    ) {
        val parameters = Tuple.tuple()

        var selectedTicketsSQLText = ""

        ticketIdList.forEach {
            if (selectedTicketsSQLText.isEmpty())
                selectedTicketsSQLText = "?"
            else
                selectedTicketsSQLText += ", ?"

            parameters.addValue(it)
        }

        val query =
            "DELETE FROM `${getTablePrefix() + tableName}` WHERE ticket_id IN ($selectedTicketsSQLText)"

        sqlConnection
            .preparedQuery(query)
            .execute(parameters)
            .await()
    }

    override suspend fun getLastMessageByTicketId(
        ticketId: Int,
        sqlConnection: SqlConnection
    ): TicketMessage? {
        val query =
            "SELECT id, user_id, ticket_id, message, `date`, `panel` FROM `${getTablePrefix() + tableName}` WHERE ticket_id = ? DESC LIMIT 1"

        val rows: RowSet<Row> = sqlConnection
            .preparedQuery(query)
            .execute(Tuple.of(ticketId))
            .await()


        if (rows.size() == 0) {
            return null
        }

        val ticketRow = rows.toList()[0]

        val ticketMessage = TicketMessage(
            ticketRow.getInteger(0),
            ticketRow.getInteger(1),
            ticketRow.getInteger(2),
            ticketRow.getString(3),
            ticketRow.getLong(4),
            ticketRow.getInteger(5)
        )

        return ticketMessage
    }

    override suspend fun isExistsById(id: Int, sqlConnection: SqlConnection): Boolean {
        val query = "SELECT COUNT(id) FROM `${getTablePrefix() + tableName}` where `id` = ?"

        val rows: RowSet<Row> = sqlConnection
            .preparedQuery(query)
            .execute(Tuple.of(id))
            .await()

        return rows.toList()[0].getInteger(0) == 1
    }
}