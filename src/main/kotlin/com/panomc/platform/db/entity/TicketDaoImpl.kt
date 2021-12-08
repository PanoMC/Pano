package com.panomc.platform.db.entity

import com.panomc.platform.db.DaoImpl
import com.panomc.platform.db.dao.TicketDao
import com.panomc.platform.db.model.Ticket
import com.panomc.platform.model.Result
import com.panomc.platform.model.Successful
import io.vertx.core.AsyncResult
import io.vertx.core.json.JsonArray
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.RowSet
import io.vertx.sqlclient.SqlConnection
import io.vertx.sqlclient.Tuple

class TicketDaoImpl(override val tableName: String = "ticket") : DaoImpl(), TicketDao {

    override fun init(): (sqlConnection: SqlConnection, handler: (asyncResult: AsyncResult<*>) -> Unit) -> Unit =
        { sqlConnection, handler ->
            sqlConnection
                .query(
                    """
                            CREATE TABLE IF NOT EXISTS `${getTablePrefix() + tableName}` (
                              `id` int NOT NULL AUTO_INCREMENT,
                              `title` MEDIUMTEXT NOT NULL,
                              `category_id` int(11) NOT NULL,
                              `user_id` int(11) NOT NULL,
                              `date` BIGINT(20) NOT NULL,
                              `last_update` BIGINT(20) NOT NULL,
                              `status` int(1) NOT NULL,
                              PRIMARY KEY (`id`)
                            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Tickets table.';
                        """
                )
                .execute {
                    handler.invoke(it)
                }
        }

    override fun count(sqlConnection: SqlConnection, handler: (count: Int?, asyncResult: AsyncResult<*>) -> Unit) {
        val query = "SELECT COUNT(id) FROM `${getTablePrefix() + tableName}`"

        sqlConnection
            .preparedQuery(query)
            .execute { queryResult ->
                if (queryResult.succeeded()) {
                    val rows: RowSet<Row> = queryResult.result()

                    handler.invoke(rows.toList()[0].getInteger(0), queryResult)
                } else
                    handler.invoke(null, queryResult)
            }
    }

    override fun countOfOpenTickets(
        sqlConnection: SqlConnection,
        handler: (count: Int?, asyncResult: AsyncResult<*>) -> Unit
    ) {
        val query = "SELECT COUNT(id) FROM `${getTablePrefix() + tableName}` WHERE status = ?"

        sqlConnection
            .preparedQuery(query)
            .execute(Tuple.of(1)) { queryResult ->
                if (queryResult.succeeded()) {
                    val rows: RowSet<Row> = queryResult.result()

                    handler.invoke(rows.toList()[0].getInteger(0), queryResult)
                } else
                    handler.invoke(null, queryResult)
            }
    }

    override fun getLast5Tickets(
        sqlConnection: SqlConnection,
        handler: (tickets: List<Ticket>?, asyncResult: AsyncResult<*>) -> Unit
    ) {
        val query =
            "SELECT id, title, category_id, user_id, `date`, `last_update`, status FROM `${getTablePrefix() + tableName}` ORDER BY `last_update` DESC, `id` DESC LIMIT 5"

        sqlConnection
            .preparedQuery(query)
            .execute { queryResult ->
                if (queryResult.succeeded()) {
                    val rows: RowSet<Row> = queryResult.result()
                    val tickets = mutableListOf<Ticket>()

                    rows.forEach { row ->
                        tickets.add(
                            Ticket(
                                row.getInteger(0),
                                row.getString(1),
                                row.getInteger(2),
                                row.getInteger(3),
                                row.getLong(4),
                                row.getLong(5),
                                row.getInteger(6)
                            )
                        )
                    }

                    handler.invoke(tickets, queryResult)
                } else
                    handler.invoke(null, queryResult)
            }
    }

    override fun getAllByPageAndPageType(
        page: Int,
        pageType: Int,
        sqlConnection: SqlConnection,
        handler: (tickets: List<Ticket>?, asyncResult: AsyncResult<*>) -> Unit
    ) {
        val query =
            "SELECT id, title, category_id, user_id, `date`, `last_update`, status FROM `${getTablePrefix() + tableName}` ${if (pageType != 2) "WHERE status = ? " else ""}ORDER BY ${if (pageType == 2) "`status` ASC, " else ""}`last_update` DESC, `id` DESC LIMIT 10 ${if (page == 1) "" else "OFFSET ${(page - 1) * 10}"}"

        val parameters = Tuple.tuple()

        if (pageType != 2)
            parameters.addInteger(pageType)

        sqlConnection
            .preparedQuery(query)
            .execute(parameters) { queryResult ->
                if (queryResult.succeeded()) {
                    val rows: RowSet<Row> = queryResult.result()
                    val tickets = mutableListOf<Ticket>()

                    rows.forEach { row ->
                        tickets.add(
                            Ticket(
                                row.getInteger(0),
                                row.getString(1),
                                row.getInteger(2),
                                row.getInteger(3),
                                row.getLong(4),
                                row.getLong(5),
                                row.getInteger(6)
                            )
                        )
                    }

                    handler.invoke(tickets, queryResult)
                } else
                    handler.invoke(null, queryResult)
            }
    }

    override fun getAllByPageAndCategoryID(
        page: Int,
        categoryID: Int,
        sqlConnection: SqlConnection,
        handler: (tickets: List<Ticket>?, asyncResult: AsyncResult<*>) -> Unit
    ) {
        val query =
            "SELECT `id`, `title`, `category_id`, `user_id`, `date`, `last_update`, `status` FROM `${getTablePrefix() + tableName}` WHERE `category_id` = ? ORDER BY `status`, `last_update` DESC, `id` DESC LIMIT 10 ${if (page == 1) "" else "OFFSET ${(page - 1) * 10}"}"

        val parameters = Tuple.tuple()

        parameters.addInteger(categoryID)

        sqlConnection
            .preparedQuery(query)
            .execute(parameters) { queryResult ->
                if (queryResult.succeeded()) {
                    val rows: RowSet<Row> = queryResult.result()
                    val tickets = mutableListOf<Ticket>()

                    rows.forEach { row ->
                        tickets.add(
                            Ticket(
                                row.getInteger(0),
                                row.getString(1),
                                row.getInteger(2),
                                row.getInteger(3),
                                row.getLong(4),
                                row.getLong(5),
                                row.getInteger(6)
                            )
                        )
                    }

                    handler.invoke(tickets, queryResult)
                } else
                    handler.invoke(null, queryResult)
            }
    }

    override fun getAllByUserIDAndPage(
        userID: Int,
        page: Int,
        sqlConnection: SqlConnection,
        handler: (tickets: List<Ticket>?, asyncResult: AsyncResult<*>) -> Unit
    ) {
        val query =
            "SELECT id, title, category_id, user_id, `date`, `last_update`, status FROM `${getTablePrefix() + tableName}` WHERE user_id = ? ORDER BY `last_update` DESC, `id` DESC LIMIT 10 ${if (page == 1) "" else "OFFSET ${(page - 1) * 10}"}"

        val parameters = Tuple.tuple()

        parameters.addInteger(userID)

        sqlConnection
            .preparedQuery(query)
            .execute(parameters) { queryResult ->
                if (queryResult.succeeded()) {
                    val rows: RowSet<Row> = queryResult.result()
                    val tickets = mutableListOf<Ticket>()

                    rows.forEach { row ->
                        tickets.add(
                            Ticket(
                                row.getInteger(0),
                                row.getString(1),
                                row.getInteger(2),
                                row.getInteger(3),
                                row.getLong(4),
                                row.getLong(5),
                                row.getInteger(6)
                            )
                        )
                    }

                    handler.invoke(tickets, queryResult)
                } else
                    handler.invoke(null, queryResult)
            }
    }

    override fun getCountByPageType(
        pageType: Int,
        sqlConnection: SqlConnection,
        handler: (count: Int?, asyncResult: AsyncResult<*>) -> Unit
    ) {

        val query =
            "SELECT COUNT(id) FROM `${getTablePrefix() + tableName}` ${if (pageType != 2) "WHERE status = ?" else ""}"

        val parameters = Tuple.tuple()

        if (pageType != 2)
            parameters.addInteger(pageType)

        sqlConnection
            .preparedQuery(query)
            .execute(parameters) { queryResult ->
                if (queryResult.succeeded()) {
                    val rows: RowSet<Row> = queryResult.result()

                    handler.invoke(rows.toList()[0].getInteger(0), queryResult)
                } else
                    handler.invoke(null, queryResult)
            }
    }

    override fun getByCategory(
        id: Int,
        sqlConnection: SqlConnection,
        handler: (tickets: List<Ticket>?, asyncResult: AsyncResult<*>) -> Unit
    ) {
        val query =
            "SELECT id, title, category_id, user_id, `date`, `last_update`, status FROM `${getTablePrefix() + tableName}` WHERE category_id = ? ORDER BY `last_update` DESC, `id` DESC LIMIT 5"

        sqlConnection
            .preparedQuery(query)
            .execute(Tuple.of(id)) { queryResult ->
                if (queryResult.succeeded()) {
                    val rows: RowSet<Row> = queryResult.result()
                    val posts = mutableListOf<Ticket>()

                    rows.forEach { row ->
                        posts.add(
                            Ticket(
                                row.getInteger(0),
                                row.getString(1),
                                row.getInteger(2),
                                row.getInteger(3),
                                row.getLong(4),
                                row.getLong(5),
                                row.getInteger(6)
                            )
                        )
                    }

                    handler.invoke(posts, queryResult)
                } else
                    handler.invoke(null, queryResult)
            }
    }

    override fun closeTickets(
        selectedTickets: JsonArray,
        sqlConnection: SqlConnection,
        handler: (result: Result?, asyncResult: AsyncResult<*>) -> Unit
    ) {
        val parameters = Tuple.tuple()

        parameters.addInteger(3)

        var selectedTicketsSQLText = ""

        selectedTickets.forEach {
            if (selectedTicketsSQLText.isEmpty())
                selectedTicketsSQLText = "?"
            else
                selectedTicketsSQLText += ", ?"

            parameters.addValue(it)
        }

        val query =
            "UPDATE `${getTablePrefix() + tableName}` SET status = ? WHERE id IN ($selectedTicketsSQLText)"

        sqlConnection
            .preparedQuery(query)
            .execute(parameters) { queryResult ->
                if (queryResult.succeeded())
                    handler.invoke(Successful(), queryResult)
                else
                    handler.invoke(null, queryResult)
            }
    }

    override fun countByCategory(
        id: Int,
        sqlConnection: SqlConnection,
        handler: (count: Int?, asyncResult: AsyncResult<*>) -> Unit
    ) {
        val query = "SELECT COUNT(id) FROM `${getTablePrefix() + tableName}` WHERE category_id = ?"

        sqlConnection
            .preparedQuery(query)
            .execute(Tuple.of(id)) { queryResult ->
                if (queryResult.succeeded()) {
                    val rows: RowSet<Row> = queryResult.result()

                    handler.invoke(rows.toList()[0].getInteger(0), queryResult)
                } else
                    handler.invoke(null, queryResult)
            }
    }

    override fun delete(
        ticketList: JsonArray,
        sqlConnection: SqlConnection,
        handler: (result: Result?, asyncResult: AsyncResult<*>) -> Unit
    ) {

        val parameters = Tuple.tuple()

        var selectedTicketsSQLText = ""

        ticketList.forEach {
            if (selectedTicketsSQLText.isEmpty())
                selectedTicketsSQLText = "?"
            else
                selectedTicketsSQLText += ", ?"

            parameters.addValue(it)
        }

        val query =
            "DELETE FROM `${getTablePrefix() + tableName}` WHERE id IN ($selectedTicketsSQLText)"

        sqlConnection
            .preparedQuery(query)
            .execute(parameters) { queryResult ->
                if (queryResult.succeeded())
                    handler.invoke(Successful(), queryResult)
                else
                    handler.invoke(null, queryResult)
            }
    }

    override fun countByUserID(
        id: Int,
        sqlConnection: SqlConnection,
        handler: (count: Int?, asyncResult: AsyncResult<*>) -> Unit
    ) {
        val query =
            "SELECT COUNT(id) FROM `${getTablePrefix() + tableName}` where user_id = ?"

        sqlConnection
            .preparedQuery(query)
            .execute(Tuple.of(id)) { queryResult ->
                if (queryResult.succeeded()) {
                    val rows: RowSet<Row> = queryResult.result()

                    handler.invoke(rows.toList()[0].getInteger(0), queryResult)
                } else
                    handler.invoke(null, queryResult)
            }
    }

    override fun getByID(
        id: Int,
        sqlConnection: SqlConnection,
        handler: (ticket: Ticket?, asyncResult: AsyncResult<*>) -> Unit
    ) {
        val query =
            "SELECT `id`, `title`, `category_id`, `user_id`, `date`, `last_update`, `status` FROM `${getTablePrefix() + tableName}` WHERE  `id` = ?"

        sqlConnection
            .preparedQuery(query)
            .execute(Tuple.of(id)) { queryResult ->
                if (queryResult.succeeded()) {
                    val rows: RowSet<Row> = queryResult.result()
                    val row = rows.toList()[0]

                    val ticket = Ticket(
                        id = row.getInteger(0),
                        title = row.getString(1),
                        categoryID = row.getInteger(2),
                        userID = row.getInteger(3),
                        date = row.getLong(4),
                        lastUpdate = row.getLong(5),
                        status = row.getInteger(6),
                    )

                    handler.invoke(ticket, queryResult)
                } else
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
            .execute(Tuple.of(id)) { queryResult ->
                if (queryResult.succeeded()) {
                    val rows: RowSet<Row> = queryResult.result()

                    handler.invoke(rows.toList()[0].getInteger(0) == 1, queryResult)
                } else
                    handler.invoke(null, queryResult)
            }
    }

    override fun makeStatus(
        id: Int,
        status: Int,
        sqlConnection: SqlConnection,
        handler: (result: Result?, asyncResult: AsyncResult<*>) -> Unit
    ) {
        val query = "UPDATE `${getTablePrefix() + tableName}` SET status = ? WHERE id = ?"

        sqlConnection
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    status,
                    id
                )
            ) { queryResult ->
                if (queryResult.succeeded())
                    handler.invoke(Successful(), queryResult)
                else
                    handler.invoke(null, queryResult)
            }
    }

    override fun updateLastUpdateDate(
        id: Int,
        date: Long,
        sqlConnection: SqlConnection,
        handler: (result: Result?, asyncResult: AsyncResult<*>) -> Unit
    ) {
        val query = "UPDATE `${getTablePrefix() + tableName}` SET last_update = ? WHERE id = ?"

        sqlConnection
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    date,
                    id
                )
            ) { queryResult ->
                if (queryResult.succeeded())
                    handler.invoke(Successful(), queryResult)
                else
                    handler.invoke(null, queryResult)
            }
    }
}