package com.panomc.platform.db.implementation

import com.panomc.platform.annotation.Dao
import com.panomc.platform.db.dao.TicketDao
import com.panomc.platform.db.model.Ticket
import com.panomc.platform.util.DashboardPeriodType
import com.panomc.platform.util.TicketPageType
import com.panomc.platform.util.TicketStatus
import com.panomc.platform.util.TimeUtil
import io.vertx.core.json.JsonArray
import io.vertx.kotlin.coroutines.await
import io.vertx.mysqlclient.MySQLClient
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.RowSet
import io.vertx.sqlclient.SqlClient
import io.vertx.sqlclient.Tuple

@Dao
class TicketDaoImpl : TicketDao() {

    override suspend fun init(sqlClient: SqlClient) {
        sqlClient
            .query(
                """
                            CREATE TABLE IF NOT EXISTS `${getTablePrefix() + tableName}` (
                              `id` bigint NOT NULL AUTO_INCREMENT,
                              `title` MEDIUMTEXT NOT NULL,
                              `categoryId` bigint NOT NULL,
                              `userId` bigint NOT NULL,
                              `date` BIGINT(20) NOT NULL,
                              `lastUpdate` BIGINT(20) NOT NULL,
                              `status` VARCHAR(255) NOT NULL,
                              PRIMARY KEY (`id`)
                            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Tickets table.';
                        """
            )
            .execute()
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

    override suspend fun countOfOpenTickets(
        sqlClient: SqlClient
    ): Long {
        val query = "SELECT COUNT(id) FROM `${getTablePrefix() + tableName}` WHERE `status` != ?"

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute(Tuple.of(TicketStatus.CLOSED))
            .await()

        return rows.toList()[0].getLong(0)
    }

    override suspend fun getLast5Tickets(
        sqlClient: SqlClient
    ): List<Ticket> {
        val query =
            "SELECT id, title, categoryId, userId, `date`, `lastUpdate`, status FROM `${getTablePrefix() + tableName}` ORDER BY `lastUpdate` DESC, `id` DESC LIMIT 5"

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute()
            .await()

        return rows.toEntities()
    }

    override suspend fun getAllByPageAndPageType(
        page: Long,
        pageType: TicketPageType,
        sqlClient: SqlClient
    ): List<Ticket> {
        val query =
            "SELECT id, title, categoryId, userId, `date`, `lastUpdate`, status FROM `${getTablePrefix() + tableName}` ${if (pageType != TicketPageType.ALL) "WHERE status = ? " else ""}ORDER BY ${if (pageType == TicketPageType.ALL) "`status` ASC, " else ""}`lastUpdate` DESC, `id` DESC LIMIT 10 ${if (page == 1L) "" else "OFFSET ${(page - 1) * 10}"}"

        val parameters = Tuple.tuple()

        if (pageType != TicketPageType.ALL)
            parameters.addString(pageType.ticketStatus!!.name)

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute(parameters)
            .await()

        return rows.toEntities()
    }

    override suspend fun getAllByPagePageTypeAndUserId(
        userId: Long,
        page: Long,
        pageType: TicketPageType,
        sqlClient: SqlClient
    ): List<Ticket> {
        val query =
            "SELECT id, title, categoryId, userId, `date`, `lastUpdate`, status FROM `${getTablePrefix() + tableName}` WHERE `userId` = ? ${if (pageType != TicketPageType.ALL) "AND status = ? " else ""}ORDER BY ${if (pageType == TicketPageType.ALL) "`status` ASC, " else ""}`lastUpdate` DESC, `id` DESC LIMIT 10 ${if (page == 1L) "" else "OFFSET ${(page - 1) * 10}"}"

        val parameters = Tuple.tuple()

        parameters.addLong(userId)

        if (pageType != TicketPageType.ALL)
            parameters.addString(pageType.ticketStatus!!.name)

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute(parameters)
            .await()

        return rows.toEntities()
    }

    override suspend fun getAllByPageAndCategoryId(
        page: Long,
        categoryId: Long,
        sqlClient: SqlClient
    ): List<Ticket> {
        val query =
            "SELECT `id`, `title`, `categoryId`, `userId`, `date`, `lastUpdate`, `status` FROM `${getTablePrefix() + tableName}` WHERE `categoryId` = ? ORDER BY `status`, `lastUpdate` DESC, `id` DESC LIMIT 10 ${if (page == 1L) "" else "OFFSET ${(page - 1) * 10}"}"

        val parameters = Tuple.tuple()

        parameters.addLong(categoryId)

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute(parameters)
            .await()

        return rows.toEntities()
    }

    override suspend fun getAllByPageCategoryIdAndUserId(
        page: Long,
        categoryId: Long,
        userId: Long,
        sqlClient: SqlClient
    ): List<Ticket> {
        val query =
            "SELECT `id`, `title`, `categoryId`, `userId`, `date`, `lastUpdate`, `status` FROM `${getTablePrefix() + tableName}` WHERE `categoryId` = ? AND `userId` = ? ORDER BY `status`, `lastUpdate` DESC, `id` DESC LIMIT 10 ${if (page == 1L) "" else "OFFSET ${(page - 1) * 10}"}"

        val parameters = Tuple.tuple()

        parameters.addLong(categoryId)
        parameters.addLong(userId)

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute(parameters)
            .await()

        return rows.toEntities()
    }

    override suspend fun getAllByUserIdAndPage(
        userId: Long,
        page: Long,
        sqlClient: SqlClient
    ): List<Ticket> {
        val query =
            "SELECT `id`, `title`, `categoryId`, `userId`, `date`, `lastUpdate`, `status` FROM `${getTablePrefix() + tableName}` WHERE userId = ? ORDER BY `lastUpdate` DESC, `id` DESC LIMIT 10 ${if (page == 1L) "" else "OFFSET ${(page - 1) * 10}"}"

        val parameters = Tuple.tuple()

        parameters.addLong(userId)

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute(parameters)
            .await()

        return rows.toEntities()
    }

    override suspend fun getCountByPageType(
        pageType: TicketPageType,
        sqlClient: SqlClient
    ): Long {
        val query =
            "SELECT COUNT(`id`) FROM `${getTablePrefix() + tableName}` ${if (pageType != TicketPageType.ALL) "WHERE `status` = ?" else ""}"

        val parameters = Tuple.tuple()

        if (pageType != TicketPageType.ALL)
            parameters.addString(pageType.ticketStatus!!.name)

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute(parameters)
            .await()

        return rows.toList()[0].getLong(0)
    }

    override suspend fun getCountByPageTypeAndUserId(
        userId: Long,
        pageType: TicketPageType,
        sqlClient: SqlClient
    ): Long {
        val query =
            "SELECT COUNT(id) FROM `${getTablePrefix() + tableName}` WHERE `userId` = ? ${if (pageType != TicketPageType.ALL) "AND status = ?" else ""}"

        val parameters = Tuple.tuple()

        parameters.addLong(userId)

        if (pageType != TicketPageType.ALL)
            parameters.addString(pageType.ticketStatus!!.name)

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute(parameters)
            .await()

        return rows.toList()[0].getLong(0)
    }

    override suspend fun getByCategory(
        id: Long,
        sqlClient: SqlClient
    ): List<Ticket> {
        val query =
            "SELECT id, title, categoryId, userId, `date`, `lastUpdate`, status FROM `${getTablePrefix() + tableName}` WHERE categoryId = ? ORDER BY `lastUpdate` DESC, `id` DESC LIMIT 5"

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute(Tuple.of(id))
            .await()

        return rows.toEntities()
    }

    override suspend fun closeTickets(
        selectedTickets: JsonArray,
        sqlClient: SqlClient
    ) {
        val parameters = Tuple.tuple()

        parameters.addString(TicketStatus.CLOSED.name)

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

        sqlClient
            .preparedQuery(query)
            .execute(parameters)
            .await()
    }

    override suspend fun closeTicketById(id: Long, sqlClient: SqlClient) {
        val parameters = Tuple.tuple()

        parameters.addString(TicketStatus.CLOSED.name)
        parameters.addLong(id)

        val query =
            "UPDATE `${getTablePrefix() + tableName}` SET `status` = ? WHERE `id` = ?"

        sqlClient
            .preparedQuery(query)
            .execute(parameters)
            .await()
    }

    override suspend fun countByCategory(
        id: Long,
        sqlClient: SqlClient
    ): Long {
        val query = "SELECT COUNT(id) FROM `${getTablePrefix() + tableName}` WHERE `categoryId` = ?"

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute(Tuple.of(id))
            .await()

        return rows.toList()[0].getLong(0)
    }

    override suspend fun countByCategoryAndUserId(id: Long, userId: Long, sqlClient: SqlClient): Long {
        val query = "SELECT COUNT(id) FROM `${getTablePrefix() + tableName}` WHERE `categoryId` = ? AND `userId` = ?"

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute(Tuple.of(id, userId))
            .await()

        return rows.toList()[0].getLong(0)
    }

    override suspend fun delete(
        ticketList: JsonArray,
        sqlClient: SqlClient
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

        sqlClient
            .preparedQuery(query)
            .execute(parameters)
            .await()
    }

    override suspend fun countByUserId(
        id: Long,
        sqlClient: SqlClient
    ): Long {
        val query =
            "SELECT COUNT(id) FROM `${getTablePrefix() + tableName}` where userId = ?"

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute(Tuple.of(id))
            .await()

        return rows.toList()[0].getLong(0)
    }

    override suspend fun getById(
        id: Long,
        sqlClient: SqlClient
    ): Ticket? {
        val query =
            "SELECT `id`, `title`, `categoryId`, `userId`, `date`, `lastUpdate`, `status` FROM `${getTablePrefix() + tableName}` WHERE  `id` = ?"

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute(Tuple.of(id))
            .await()

        if (rows.size() == 0) {
            return null
        }

        val row = rows.toList()[0]

        return row.toEntity()
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

    override suspend fun isIdBelongToUserId(id: Long, userId: Long, sqlClient: SqlClient): Boolean {
        val query = "SELECT COUNT(id) FROM `${getTablePrefix() + tableName}` where `id` = ? AND `userId` = ?"

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute(Tuple.of(id, userId))
            .await()

        return rows.toList()[0].getLong(0) == 1L
    }

    override suspend fun existsByIdAndUserId(id: Long, userId: Long, sqlClient: SqlClient): Boolean {
        val query = "SELECT COUNT(id) FROM `${getTablePrefix() + tableName}` where `id` = ? and `userId` = ?"

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute(Tuple.of(id, userId))
            .await()

        return rows.toList()[0].getLong(0) == 1L
    }

    override suspend fun makeStatus(
        id: Long,
        status: TicketStatus,
        sqlClient: SqlClient
    ) {
        val query = "UPDATE `${getTablePrefix() + tableName}` SET status = ? WHERE id = ?"

        sqlClient
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
        sqlClient: SqlClient
    ) {
        val query = "UPDATE `${getTablePrefix() + tableName}` SET lastUpdate = ? WHERE id = ?"

        sqlClient
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    date,
                    id
                )
            )
            .await()
    }

    override suspend fun add(ticket: Ticket, sqlClient: SqlClient): Long {
        val query =
            "INSERT INTO `${getTablePrefix() + tableName}` (`title`, `categoryId`, `userId`, `date`, `lastUpdate`, `status`) VALUES (?, ?, ?, ?, ?, ?)"

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    ticket.title,
                    ticket.categoryId,
                    ticket.userId,
                    ticket.date,
                    ticket.lastUpdate,
                    ticket.status.name
                )
            )
            .await()

        return rows.property(MySQLClient.LAST_INSERTED_ID)
    }

    override suspend fun getDatesByPeriod(
        dashboardPeriodType: DashboardPeriodType,
        sqlClient: SqlClient
    ): List<Long> {
        val query = "SELECT `date` FROM `${getTablePrefix() + tableName}` WHERE `date` > ?"

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute(Tuple.of(TimeUtil.getTimeToCompareByDashboardPeriodType(dashboardPeriodType)))
            .await()

        return rows.toList().map { it.getLong(0) }
    }

    override suspend fun areIdListExist(ids: List<Long>, sqlClient: SqlClient): Boolean {
        var listText = ""

        ids.forEach { id ->
            if (listText == "")
                listText = "'$id'"
            else
                listText += ", '$id'"
        }

        val query = "SELECT COUNT(id) FROM `${getTablePrefix() + tableName}` where `id` IN ($listText)"

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute()
            .await()

        return rows.toList()[0].getLong(0) == ids.size.toLong()
    }

    override suspend fun removeTicketCategoriesByCategoryId(categoryId: Long, sqlClient: SqlClient) {
        val query = "UPDATE `${getTablePrefix() + tableName}` SET `categoryId` = ? WHERE `categoryId` = ?"

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

    override suspend fun getByUserId(
        userId: Long,
        sqlClient: SqlClient
    ): List<Ticket> {
        val query =
            "SELECT id, title, categoryId, userId, `date`, `lastUpdate`, status FROM `${getTablePrefix() + tableName}` WHERE userId = ? ORDER BY `lastUpdate` DESC, `id` DESC"

        val parameters = Tuple.tuple()

        parameters.addLong(userId)

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute(parameters)
            .await()

        return rows.toEntities()
    }

    override suspend fun getStatusById(id: Long, sqlClient: SqlClient): TicketStatus? {
        val query =
            "SELECT `status` FROM `${getTablePrefix() + tableName}` WHERE `id` = ?"

        val parameters = Tuple.tuple()

        parameters.addLong(id)

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute(parameters)
            .await()

        if (rows.size() == 0) {
            return null
        }

        val row = rows.toList()[0]

        return TicketStatus.valueOf(row.getString(0))
    }
}