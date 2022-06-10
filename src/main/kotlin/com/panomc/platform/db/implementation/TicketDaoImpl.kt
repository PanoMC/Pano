package com.panomc.platform.db.implementation

import com.panomc.platform.annotation.Dao
import com.panomc.platform.db.DaoImpl
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.dao.TicketDao
import com.panomc.platform.db.model.Ticket
import com.panomc.platform.util.TicketStatus
import io.vertx.core.json.JsonArray
import io.vertx.kotlin.coroutines.await
import io.vertx.mysqlclient.MySQLClient
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.RowSet
import io.vertx.sqlclient.SqlConnection
import io.vertx.sqlclient.Tuple

@Dao
class TicketDaoImpl(databaseManager: DatabaseManager) : DaoImpl(databaseManager, "ticket"), TicketDao {

    override suspend fun init(sqlConnection: SqlConnection) {
        sqlConnection
            .query(
                """
                            CREATE TABLE IF NOT EXISTS `${getTablePrefix() + tableName}` (
                              `id` bigint NOT NULL AUTO_INCREMENT,
                              `title` MEDIUMTEXT NOT NULL,
                              `category_id` bigint NOT NULL,
                              `user_id` bigint NOT NULL,
                              `date` BIGINT(20) NOT NULL,
                              `last_update` BIGINT(20) NOT NULL,
                              `status` int(1) NOT NULL,
                              PRIMARY KEY (`id`)
                            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Tickets table.';
                        """
            )
            .execute()
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

    override suspend fun countOfOpenTickets(
        sqlConnection: SqlConnection
    ): Long {
        val query = "SELECT COUNT(id) FROM `${getTablePrefix() + tableName}` WHERE status = ?"

        val rows: RowSet<Row> = sqlConnection
            .preparedQuery(query)
            .execute(Tuple.of(TicketStatus.WAITING_REPLY.value))
            .await()

        return rows.toList()[0].getLong(0)
    }

    override suspend fun getLast5Tickets(
        sqlConnection: SqlConnection
    ): List<Ticket> {
        val query =
            "SELECT id, title, category_id, user_id, `date`, `last_update`, status FROM `${getTablePrefix() + tableName}` ORDER BY `last_update` DESC, `id` DESC LIMIT 5"

        val rows: RowSet<Row> = sqlConnection
            .preparedQuery(query)
            .execute()
            .await()

        return Ticket.from(rows)
    }

    override suspend fun getAllByPageAndPageType(
        page: Long,
        pageType: TicketStatus,
        sqlConnection: SqlConnection
    ): List<Ticket> {
        val query =
            "SELECT id, title, category_id, user_id, `date`, `last_update`, status FROM `${getTablePrefix() + tableName}` ${if (pageType != TicketStatus.ALL) "WHERE status = ? " else ""}ORDER BY ${if (pageType == TicketStatus.ALL) "`status` ASC, " else ""}`last_update` DESC, `id` DESC LIMIT 10 ${if (page == 1L) "" else "OFFSET ${(page - 1) * 10}"}"

        val parameters = Tuple.tuple()

        if (pageType != TicketStatus.ALL)
            parameters.addInteger(pageType.value)

        val rows: RowSet<Row> = sqlConnection
            .preparedQuery(query)
            .execute(parameters)
            .await()

        return Ticket.from(rows)
    }

    override suspend fun getAllByPagePageTypeAndUserId(
        userId: Long,
        page: Long,
        pageType: TicketStatus,
        sqlConnection: SqlConnection
    ): List<Ticket> {
        val query =
            "SELECT id, title, category_id, user_id, `date`, `last_update`, status FROM `${getTablePrefix() + tableName}` WHERE `user_id` = ? ${if (pageType != TicketStatus.ALL) "AND status = ? " else ""}ORDER BY ${if (pageType == TicketStatus.ALL) "`status` ASC, " else ""}`last_update` DESC, `id` DESC LIMIT 10 ${if (page == 1L) "" else "OFFSET ${(page - 1) * 10}"}"

        val parameters = Tuple.tuple()

        parameters.addLong(userId)

        if (pageType != TicketStatus.ALL)
            parameters.addInteger(pageType.value)

        val rows: RowSet<Row> = sqlConnection
            .preparedQuery(query)
            .execute(parameters)
            .await()

        return Ticket.from(rows)
    }

    override suspend fun getAllByPageAndCategoryId(
        page: Long,
        categoryId: Long,
        sqlConnection: SqlConnection
    ): List<Ticket> {
        val query =
            "SELECT `id`, `title`, `category_id`, `user_id`, `date`, `last_update`, `status` FROM `${getTablePrefix() + tableName}` WHERE `category_id` = ? ORDER BY `status`, `last_update` DESC, `id` DESC LIMIT 10 ${if (page == 1L) "" else "OFFSET ${(page - 1) * 10}"}"

        val parameters = Tuple.tuple()

        parameters.addLong(categoryId)

        val rows: RowSet<Row> = sqlConnection
            .preparedQuery(query)
            .execute(parameters)
            .await()

        return Ticket.from(rows)
    }

    override suspend fun getAllByPageCategoryIdAndUserId(
        page: Long,
        categoryId: Long,
        userId: Long,
        sqlConnection: SqlConnection
    ): List<Ticket> {
        val query =
            "SELECT `id`, `title`, `category_id`, `user_id`, `date`, `last_update`, `status` FROM `${getTablePrefix() + tableName}` WHERE `category_id` = ? AND `user_id` = ? ORDER BY `status`, `last_update` DESC, `id` DESC LIMIT 10 ${if (page == 1L) "" else "OFFSET ${(page - 1) * 10}"}"

        val parameters = Tuple.tuple()

        parameters.addLong(categoryId)
        parameters.addLong(userId)

        val rows: RowSet<Row> = sqlConnection
            .preparedQuery(query)
            .execute(parameters)
            .await()

        return Ticket.from(rows)
    }

    override suspend fun getAllByUserIdAndPage(
        userId: Long,
        page: Long,
        sqlConnection: SqlConnection
    ): List<Ticket> {
        val query =
            "SELECT id, title, category_id, user_id, `date`, `last_update`, status FROM `${getTablePrefix() + tableName}` WHERE user_id = ? ORDER BY `last_update` DESC, `id` DESC LIMIT 10 ${if (page == 1L) "" else "OFFSET ${(page - 1) * 10}"}"

        val parameters = Tuple.tuple()

        parameters.addLong(userId)

        val rows: RowSet<Row> = sqlConnection
            .preparedQuery(query)
            .execute(parameters)
            .await()

        return Ticket.from(rows)
    }

    override suspend fun getCountByPageType(
        pageType: TicketStatus,
        sqlConnection: SqlConnection
    ): Long {
        val query =
            "SELECT COUNT(id) FROM `${getTablePrefix() + tableName}` ${if (pageType != TicketStatus.ALL) "WHERE status = ?" else ""}"

        val parameters = Tuple.tuple()

        if (pageType != TicketStatus.ALL)
            parameters.addInteger(pageType.value)

        val rows: RowSet<Row> = sqlConnection
            .preparedQuery(query)
            .execute(parameters)
            .await()

        return rows.toList()[0].getLong(0)
    }

    override suspend fun getCountByPageTypeAndUserId(
        userId: Long,
        pageType: TicketStatus,
        sqlConnection: SqlConnection
    ): Long {
        val query =
            "SELECT COUNT(id) FROM `${getTablePrefix() + tableName}` WHERE `user_id` = ? ${if (pageType != TicketStatus.ALL) "AND status = ?" else ""}"

        val parameters = Tuple.tuple()

        parameters.addLong(userId)

        if (pageType != TicketStatus.ALL)
            parameters.addInteger(pageType.value)

        val rows: RowSet<Row> = sqlConnection
            .preparedQuery(query)
            .execute(parameters)
            .await()

        return rows.toList()[0].getLong(0)
    }

    override suspend fun getByCategory(
        id: Long,
        sqlConnection: SqlConnection
    ): List<Ticket> {
        val query =
            "SELECT id, title, category_id, user_id, `date`, `last_update`, status FROM `${getTablePrefix() + tableName}` WHERE category_id = ? ORDER BY `last_update` DESC, `id` DESC LIMIT 5"

        val rows: RowSet<Row> = sqlConnection
            .preparedQuery(query)
            .execute(Tuple.of(id))
            .await()

        return Ticket.from(rows)
    }

    override suspend fun closeTickets(
        selectedTickets: JsonArray,
        sqlConnection: SqlConnection
    ) {
        val parameters = Tuple.tuple()

        parameters.addInteger(TicketStatus.CLOSED.value)

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
            .execute(parameters)
            .await()
    }

    override suspend fun closeTicketById(id: Long, sqlConnection: SqlConnection) {
        val parameters = Tuple.tuple()

        parameters.addInteger(TicketStatus.CLOSED.value)
        parameters.addLong(id)

        val query =
            "UPDATE `${getTablePrefix() + tableName}` SET `status` = ? WHERE `id` = ?"

        sqlConnection
            .preparedQuery(query)
            .execute(parameters)
            .await()
    }

    override suspend fun countByCategory(
        id: Long,
        sqlConnection: SqlConnection
    ): Long {
        val query = "SELECT COUNT(id) FROM `${getTablePrefix() + tableName}` WHERE `category_id` = ?"

        val rows: RowSet<Row> = sqlConnection
            .preparedQuery(query)
            .execute(Tuple.of(id))
            .await()

        return rows.toList()[0].getLong(0)
    }

    override suspend fun countByCategoryAndUserId(id: Long, userId: Long, sqlConnection: SqlConnection): Long {
        val query = "SELECT COUNT(id) FROM `${getTablePrefix() + tableName}` WHERE `category_id` = ? AND `user_id` = ?"

        val rows: RowSet<Row> = sqlConnection
            .preparedQuery(query)
            .execute(Tuple.of(id, userId))
            .await()

        return rows.toList()[0].getLong(0)
    }

    override suspend fun delete(
        ticketList: JsonArray,
        sqlConnection: SqlConnection
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
            .execute(parameters)
            .await()
    }

    override suspend fun countByUserId(
        id: Long,
        sqlConnection: SqlConnection
    ): Long {
        val query =
            "SELECT COUNT(id) FROM `${getTablePrefix() + tableName}` where user_id = ?"

        val rows: RowSet<Row> = sqlConnection
            .preparedQuery(query)
            .execute(Tuple.of(id))
            .await()

        return rows.toList()[0].getLong(0)
    }

    override suspend fun getById(
        id: Long,
        sqlConnection: SqlConnection
    ): Ticket? {
        val query =
            "SELECT `id`, `title`, `category_id`, `user_id`, `date`, `last_update`, `status` FROM `${getTablePrefix() + tableName}` WHERE  `id` = ?"

        val rows: RowSet<Row> = sqlConnection
            .preparedQuery(query)
            .execute(Tuple.of(id))
            .await()

        if (rows.size() == 0) {
            return null
        }

        val row = rows.toList()[0]

        return Ticket.from(row)
    }

    override suspend fun isExistsById(
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

    override suspend fun isBelongToUserIdsById(id: Long, userId: Long, sqlConnection: SqlConnection): Boolean {
        val query = "SELECT COUNT(id) FROM `${getTablePrefix() + tableName}` where `id` = ? AND `user_id` = ?"

        val rows: RowSet<Row> = sqlConnection
            .preparedQuery(query)
            .execute(Tuple.of(id, userId))
            .await()

        return rows.toList()[0].getLong(0) == 1L
    }

    override suspend fun isExistsByIdAndUserId(id: Long, userId: Long, sqlConnection: SqlConnection): Boolean {
        val query = "SELECT COUNT(id) FROM `${getTablePrefix() + tableName}` where `id` = ? and `user_id` = ?"

        val rows: RowSet<Row> = sqlConnection
            .preparedQuery(query)
            .execute(Tuple.of(id, userId))
            .await()

        return rows.toList()[0].getLong(0) == 1L
    }

    override suspend fun makeStatus(
        id: Long,
        status: Int,
        sqlConnection: SqlConnection
    ) {
        val query = "UPDATE `${getTablePrefix() + tableName}` SET status = ? WHERE id = ?"

        sqlConnection
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    status,
                    id
                )
            )
            .await()
    }

    override suspend fun updateLastUpdateDate(
        id: Long,
        date: Long,
        sqlConnection: SqlConnection
    ) {
        val query = "UPDATE `${getTablePrefix() + tableName}` SET last_update = ? WHERE id = ?"

        sqlConnection
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    date,
                    id
                )
            )
            .await()
    }

    override suspend fun save(ticket: Ticket, sqlConnection: SqlConnection): Long {
        val query =
            "INSERT INTO `${getTablePrefix() + tableName}` (`title`, `category_id`, `user_id`, `date`, `last_update`, `status`) VALUES (?, ?, ?, ?, ?, ?)"

        val rows: RowSet<Row> = sqlConnection
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    ticket.title,
                    ticket.categoryId,
                    ticket.userId,
                    ticket.date,
                    ticket.lastUpdate,
                    ticket.status.value
                )
            )
            .await()

        return rows.property(MySQLClient.LAST_INSERTED_ID)
    }
}