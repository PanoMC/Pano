package com.panomc.platform.db.implementation

import com.panomc.platform.annotation.Dao
import com.panomc.platform.db.DaoImpl
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.dao.TicketCategoryDao
import com.panomc.platform.db.model.TicketCategory
import com.panomc.platform.util.TextUtil
import io.vertx.kotlin.coroutines.await
import io.vertx.mysqlclient.MySQLClient
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.RowSet
import io.vertx.sqlclient.SqlClient
import io.vertx.sqlclient.Tuple

@Dao
class TicketCategoryDaoImpl(databaseManager: DatabaseManager) : DaoImpl(databaseManager, "ticket_category"),
    TicketCategoryDao {

    override suspend fun init(sqlClient: SqlClient) {
        sqlClient
            .query(
                """
                            CREATE TABLE IF NOT EXISTS `${getTablePrefix() + tableName}` (
                              `id` bigint NOT NULL AUTO_INCREMENT,
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
        sqlClient: SqlClient
    ): List<TicketCategory> {
        val query = "SELECT `id`, `title`, `description`, `url` FROM `${getTablePrefix() + tableName}`"

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute()
            .await()

        return TicketCategory.from(rows)
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

    override suspend fun existsByUrl(
        url: String,
        sqlClient: SqlClient,
    ): Boolean {
        val query = "SELECT COUNT(id) FROM `${getTablePrefix() + tableName}` where `url` = ?"

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute(Tuple.of(url))
            .await()

        return rows.toList()[0].getLong(0) == 1L
    }

    override suspend fun deleteById(
        id: Long,
        sqlClient: SqlClient
    ) {
        val query = "DELETE FROM `${getTablePrefix() + tableName}` WHERE `id` = ?"

        sqlClient
            .preparedQuery(query)
            .execute(Tuple.of(id))
            .await()
    }

    override suspend fun add(
        ticketCategory: TicketCategory,
        sqlClient: SqlClient
    ): Long {
        val query =
            "INSERT INTO `${getTablePrefix() + tableName}` (`title`, `description`, `url`) VALUES (?, ?, ?)"

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    ticketCategory.title,
                    ticketCategory.description,
                    TextUtil.convertStringToUrl(ticketCategory.title)
                )
            )
            .await()

        return rows.property(MySQLClient.LAST_INSERTED_ID)
    }

    override suspend fun updateUrlById(id: Long, newUrl: String, sqlClient: SqlClient) {
        val query =
            "UPDATE `${getTablePrefix() + tableName}` SET `url` = ? WHERE `id` = ?"

        sqlClient
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    newUrl,
                    id
                )
            )
            .await()
    }

    override suspend fun update(
        ticketCategory: TicketCategory,
        sqlClient: SqlClient
    ) {
        val query =
            "UPDATE `${getTablePrefix() + tableName}` SET `title` = ?, `description` = ?, `url` = ? WHERE `id` = ?"

        sqlClient
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    ticketCategory.title,
                    ticketCategory.description,
                    TextUtil.convertStringToUrl(ticketCategory.title),
                    ticketCategory.id
                )
            )
            .await()
    }

    override suspend fun count(sqlClient: SqlClient): Long {
        val query =
            "SELECT COUNT(id) FROM `${getTablePrefix() + tableName}`"

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute()
            .await()

        return rows.toList()[0].getLong(0)
    }

    override suspend fun getByPage(
        page: Long,
        sqlClient: SqlClient
    ): List<TicketCategory> {
        val query =
            "SELECT `id`, `title`, `description`, `url` FROM `${getTablePrefix() + tableName}` ORDER BY id DESC ${if (page != 0L) "LIMIT 10 OFFSET " + (page - 1) * 10 else ""}"

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute()
            .await()

        return TicketCategory.from(rows)
    }

    override suspend fun getById(
        id: Long,
        sqlClient: SqlClient
    ): TicketCategory? {
        val query =
            "SELECT `id`, `title`, `description`, `url` FROM `${getTablePrefix() + tableName}` WHERE  `id` = ?"

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute(Tuple.of(id))
            .await()

        if (rows.size() == 0) {
            return null
        }

        val row = rows.toList()[0]

        return TicketCategory.from(row)
    }

    override suspend fun getByUrl(
        url: String,
        sqlClient: SqlClient
    ): TicketCategory? {
        val query =
            "SELECT `id`, `title`, `description`, `url` FROM `${getTablePrefix() + tableName}` WHERE  `url` = ?"

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute(Tuple.of(url))
            .await()

        if (rows.size() == 0) {
            return null
        }

        val row = rows.toList()[0]

        return TicketCategory.from(row)
    }

    override suspend fun getByIdList(
        ticketCategoryIdList: List<Long>,
        sqlClient: SqlClient
    ): Map<Long, TicketCategory> {
        var listText = ""

        ticketCategoryIdList.forEach { id ->
            if (listText == "")
                listText = "'$id'"
            else
                listText += ", '$id'"
        }

        val query =
            "SELECT `id`, `title`, `description`, `url` FROM `${getTablePrefix() + tableName}` WHERE  `id` IN ($listText)"

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute()
            .await()

        return TicketCategory.from(rows).associateBy { it.id }
    }
}