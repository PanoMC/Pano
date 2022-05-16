package com.panomc.platform.db.implementation

import com.panomc.platform.annotation.Dao
import com.panomc.platform.db.DaoImpl
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.dao.TicketCategoryDao
import com.panomc.platform.db.model.TicketCategory
import com.panomc.platform.util.TextUtil
import io.vertx.kotlin.coroutines.await
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.RowSet
import io.vertx.sqlclient.SqlConnection
import io.vertx.sqlclient.Tuple

@Dao
class TicketCategoryDaoImpl(databaseManager: DatabaseManager) : DaoImpl(databaseManager, "ticket_category"),
    TicketCategoryDao {

    override suspend fun init(sqlConnection: SqlConnection) {
        sqlConnection
            .query(
                """
                            CREATE TABLE IF NOT EXISTS `${getTablePrefix() + tableName}` (
                              `id` int NOT NULL AUTO_INCREMENT,
                              `title` MEDIUMTEXT NOT NULL,
                              `description` text,
                              `url` mediumtext NOT NULL,
                              PRIMARY KEY (`id`)
                            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Ticket category table.';
                        """
            )
            .execute()
            .await()
    }

    override suspend fun getAll(
        sqlConnection: SqlConnection
    ): List<TicketCategory> {
        val query = "SELECT `id`, `title`, `description`, `url` FROM `${getTablePrefix() + tableName}`"
        val categories = mutableListOf<TicketCategory>()

        val rows: RowSet<Row> = sqlConnection
            .preparedQuery(query)
            .execute()
            .await()

        rows.forEach { row ->
            categories.add(
                TicketCategory(
                    row.getInteger(0),
                    row.getString(1),
                    row.getString(2),
                    row.getString(3)
                )
            )
        }

        return categories
    }

    override suspend fun isExistsByID(
        id: Int,
        sqlConnection: SqlConnection
    ): Boolean {
        val query = "SELECT COUNT(id) FROM `${getTablePrefix() + tableName}` where `id` = ?"

        val rows: RowSet<Row> = sqlConnection
            .preparedQuery(query)
            .execute(Tuple.of(id))
            .await()

        return rows.toList()[0].getInteger(0) == 1
    }

    override suspend fun isExistsByURL(
        url: String,
        sqlConnection: SqlConnection,
    ): Boolean {
        val query = "SELECT COUNT(id) FROM `${getTablePrefix() + tableName}` where `url` = ?"

        val rows: RowSet<Row> = sqlConnection
            .preparedQuery(query)
            .execute(Tuple.of(url))
            .await()

        return rows.toList()[0].getInteger(0) == 1
    }

    override suspend fun deleteByID(
        id: Int,
        sqlConnection: SqlConnection
    ) {
        val query = "DELETE FROM `${getTablePrefix() + tableName}` WHERE `id` = ?"

        sqlConnection
            .preparedQuery(query)
            .execute(Tuple.of(id))
            .await()
    }

    override suspend fun add(
        ticketCategory: TicketCategory,
        sqlConnection: SqlConnection
    ) {
        val query =
            "INSERT INTO `${getTablePrefix() + tableName}` (`title`, `description`, `url`) VALUES (?, ?, ?)"

        sqlConnection
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    ticketCategory.title,
                    ticketCategory.description,
                    TextUtil.convertStringToURL(ticketCategory.title)
                )
            )
            .await()
    }

    override suspend fun update(
        ticketCategory: TicketCategory,
        sqlConnection: SqlConnection
    ) {
        val query =
            "UPDATE `${getTablePrefix() + tableName}` SET `title` = ?, `description` = ?, `url` = ? WHERE `id` = ?"

        sqlConnection
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    ticketCategory.title,
                    ticketCategory.description,
                    TextUtil.convertStringToURL(ticketCategory.title),
                    ticketCategory.id
                )
            )
            .await()
    }

    override suspend fun count(sqlConnection: SqlConnection): Int {
        val query =
            "SELECT COUNT(id) FROM `${getTablePrefix() + tableName}`"

        val rows: RowSet<Row> = sqlConnection
            .preparedQuery(query)
            .execute()
            .await()

        return rows.toList()[0].getInteger(0)
    }

    override suspend fun getByPage(
        page: Int,
        sqlConnection: SqlConnection
    ): List<TicketCategory> {
        val query =
            "SELECT `id`, `title`, `description`, `url` FROM `${getTablePrefix() + tableName}` ORDER BY id DESC LIMIT 10 OFFSET ${(page - 1) * 10}"

        val rows: RowSet<Row> = sqlConnection
            .preparedQuery(query)
            .execute()
            .await()

        val categories = mutableListOf<TicketCategory>()

        if (rows.size() > 0)
            rows.forEach { row ->
                categories.add(
                    TicketCategory(
                        row.getInteger(0),
                        row.getString(1),
                        row.getString(2),
                        row.getString(3)
                    )
                )
            }

        return categories
    }

    override suspend fun getByID(
        id: Int,
        sqlConnection: SqlConnection
    ): TicketCategory? {
        val query =
            "SELECT `id`, `title`, `description`, `url` FROM `${getTablePrefix() + tableName}` WHERE  `id` = ?"

        val rows: RowSet<Row> = sqlConnection
            .preparedQuery(query)
            .execute(Tuple.of(id))
            .await()

        if (rows.size() == 0) {
            return null
        }

        val row = rows.toList()[0]

        val ticket = TicketCategory(
            id = row.getInteger(0),
            title = row.getString(1),
            description = row.getString(2),
            url = row.getString(3)
        )

        return ticket
    }

    override suspend fun getByURL(
        url: String,
        sqlConnection: SqlConnection
    ): TicketCategory? {
        val query =
            "SELECT `id`, `title`, `description`, `url` FROM `${getTablePrefix() + tableName}` WHERE  `url` = ?"

        val rows: RowSet<Row> = sqlConnection
            .preparedQuery(query)
            .execute(Tuple.of(url))
            .await()

        if (rows.size() == 0) {
            return null
        }

        val row = rows.toList()[0]

        val ticket = TicketCategory(
            id = row.getInteger(0),
            title = row.getString(1),
            description = row.getString(2),
            url = row.getString(3)
        )

        return ticket
    }

    override suspend fun getByIDList(
        ticketCategoryIdList: List<Int>,
        sqlConnection: SqlConnection
    ): Map<Int, TicketCategory> {
        var listText = ""

        ticketCategoryIdList.forEach { id ->
            if (listText == "")
                listText = "'$id'"
            else
                listText += ", '$id'"
        }

        val query =
            "SELECT `id`, `title`, `description`, `url` FROM `${getTablePrefix() + tableName}` WHERE  `id` IN ($listText)"

        val rows: RowSet<Row> = sqlConnection
            .preparedQuery(query)
            .execute()
            .await()

        val ticketList = mutableMapOf<Int, TicketCategory>()

        rows.forEach { row ->
            ticketList[row.getInteger(0)] = TicketCategory(
                id = row.getInteger(0),
                title = row.getString(1),
                description = row.getString(2),
                url = row.getString(3)
            )
        }

        return ticketList
    }
}