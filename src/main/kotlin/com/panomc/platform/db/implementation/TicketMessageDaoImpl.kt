package com.panomc.platform.db.implementation

import com.panomc.platform.db.DaoImpl
import com.panomc.platform.db.dao.TicketMessageDao
import com.panomc.platform.db.model.TicketMessage
import com.panomc.platform.model.Result
import com.panomc.platform.model.Successful
import io.vertx.core.AsyncResult
import io.vertx.core.json.JsonArray
import io.vertx.mysqlclient.MySQLClient
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.RowSet
import io.vertx.sqlclient.SqlConnection
import io.vertx.sqlclient.Tuple

class TicketMessageDaoImpl(override val tableName: String = "ticket_message") : DaoImpl(), TicketMessageDao {
    override fun init(): (sqlConnection: SqlConnection, handler: (asyncResult: AsyncResult<*>) -> Unit) -> Unit =
        { sqlConnection, handler ->
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
                .execute {
                    handler.invoke(it)
                }
        }

    override fun getByTicketIDAndPage(
        ticketID: Int,
        sqlConnection: SqlConnection,
        handler: (messages: List<TicketMessage>?, asyncResult: AsyncResult<*>) -> Unit
    ) {
        val query =
            "SELECT id, user_id, ticket_id, message, `date`, `panel` FROM `${getTablePrefix() + tableName}` WHERE ticket_id = ? ORDER BY id DESC LIMIT 5"

        sqlConnection
            .preparedQuery(query)
            .execute(Tuple.of(ticketID)) { queryResult ->
                if (queryResult.succeeded()) {
                    val rows: RowSet<Row> = queryResult.result()
                    val messages = mutableListOf<TicketMessage>()

                    if (rows.size() > 0)
                        rows.forEach { row ->
                            messages.add(
                                TicketMessage(
                                    id = row.getInteger(0),
                                    userID = row.getInteger(1),
                                    ticketID = row.getInteger(2),
                                    message = row.getString(3),
                                    date = row.getLong(4),
                                    panel = row.getInteger(5)
                                )
                            )
                        }

                    handler.invoke(messages, queryResult)
                } else
                    handler.invoke(null, queryResult)
            }
    }

    override fun getByTicketIDPageAndStartFromID(
        lastMessageID: Int,
        ticketID: Int,
        sqlConnection: SqlConnection,
        handler: (messages: List<TicketMessage>?, asyncResult: AsyncResult<*>) -> Unit
    ) {
        val query =
            "SELECT id, user_id, ticket_id, message, `date`, `panel` FROM `${getTablePrefix() + tableName}` WHERE ticket_id = ? and id < ? ORDER BY id DESC LIMIT 5"

        sqlConnection
            .preparedQuery(query)
            .execute(Tuple.of(ticketID, lastMessageID)) { queryResult ->
                if (queryResult.succeeded()) {
                    val rows: RowSet<Row> = queryResult.result()
                    val messages = mutableListOf<TicketMessage>()

                    if (rows.size() > 0)
                        rows.forEach { row ->
                            messages.add(
                                TicketMessage(
                                    id = row.getInteger(0),
                                    userID = row.getInteger(1),
                                    ticketID = row.getInteger(2),
                                    message = row.getString(3),
                                    date = row.getLong(4),
                                    panel = row.getInteger(5)
                                )
                            )
                        }

                    handler.invoke(messages, queryResult)
                } else
                    handler.invoke(null, queryResult)
            }
    }

    override fun getCountByTicketID(
        ticketID: Int,
        sqlConnection: SqlConnection,
        handler: (count: Int?, asyncResult: AsyncResult<*>) -> Unit
    ) {
        val query =
            "SELECT COUNT(id) FROM `${getTablePrefix() + tableName}` where ticket_id = ?"

        sqlConnection
            .preparedQuery(query)
            .execute(Tuple.of(ticketID)) { queryResult ->
                if (queryResult.succeeded()) {
                    val rows: RowSet<Row> = queryResult.result()

                    handler.invoke(rows.toList()[0].getInteger(0), queryResult)
                } else
                    handler.invoke(null, queryResult)
            }
    }

    override fun addMessage(
        ticketMessage: TicketMessage,
        sqlConnection: SqlConnection,
        handler: (result: Result?, asyncResult: AsyncResult<*>) -> Unit
    ) {
        val query =
            "INSERT INTO `${getTablePrefix() + tableName}` (user_id, ticket_id, message, `date`, panel) VALUES (?, ?, ?, ?, ?)"

        sqlConnection
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    ticketMessage.userID,
                    ticketMessage.ticketID,
                    ticketMessage.message,
                    ticketMessage.date,
                    ticketMessage.panel,
                )
            ) { queryResult ->
                if (queryResult.succeeded()) {
                    val rows: RowSet<Row> = queryResult.result()

                    handler.invoke(Successful(mapOf("id" to rows.property(MySQLClient.LAST_INSERTED_ID))), queryResult)
                } else
                    handler.invoke(null, queryResult)
            }
    }

    override fun deleteByTicketIDList(
        ticketIDList: JsonArray,
        sqlConnection: SqlConnection,
        handler: (result: Result?, asyncResult: AsyncResult<*>) -> Unit
    ) {
        val parameters = Tuple.tuple()

        var selectedTicketsSQLText = ""

        ticketIDList.forEach {
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
            .execute(parameters) { queryResult ->
                if (queryResult.succeeded())
                    handler.invoke(Successful(), queryResult)
                else
                    handler.invoke(null, queryResult)
            }
    }

    override fun getLastMessageByTicketID(
        ticketID: Int,
        sqlConnection: SqlConnection,
        handler: (ticketMessage: TicketMessage?, asyncResult: AsyncResult<*>) -> Unit
    ) {
        val query =
            "SELECT id, user_id, ticket_id, message, `date`, `panel` FROM `${getTablePrefix() + tableName}` WHERE ticket_id = ? DESC LIMIT 1"

        sqlConnection
            .preparedQuery(query)
            .execute(Tuple.of(ticketID)) { queryResult ->
                if (queryResult.succeeded()) {
                    val rows: RowSet<Row> = queryResult.result()
                    val ticketRow = rows.toList()[0]

                    val ticketMessage = TicketMessage(
                        ticketRow.getInteger(0),
                        ticketRow.getInteger(1),
                        ticketRow.getInteger(2),
                        ticketRow.getString(3),
                        ticketRow.getLong(4),
                        ticketRow.getInteger(5)
                    )

                    handler.invoke(ticketMessage, queryResult)
                } else
                    handler.invoke(null, queryResult)
            }
    }
}