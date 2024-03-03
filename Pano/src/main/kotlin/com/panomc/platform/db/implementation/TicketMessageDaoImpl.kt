package com.panomc.platform.db.implementation

import com.panomc.platform.annotation.Dao
import com.panomc.platform.db.dao.TicketMessageDao
import com.panomc.platform.db.model.TicketMessage
import io.vertx.core.json.JsonArray
import io.vertx.kotlin.coroutines.await
import io.vertx.mysqlclient.MySQLClient
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.RowSet
import io.vertx.sqlclient.SqlClient
import io.vertx.sqlclient.Tuple

@Dao
class TicketMessageDaoImpl : TicketMessageDao() {
    override suspend fun init(sqlClient: SqlClient) {
        sqlClient
            .query(
                """
                            CREATE TABLE IF NOT EXISTS `${getTablePrefix() + tableName}` (
                              `id` bigint NOT NULL AUTO_INCREMENT,
                              `userId` bigint NOT NULL,
                              `ticketId` bigint NOT NULL,
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

    override suspend fun getByTicketId(
        ticketId: Long,
        sqlClient: SqlClient
    ): List<TicketMessage> {
        val query =
            "SELECT id, userId, ticketId, message, `date`, `panel` FROM `${getTablePrefix() + tableName}` WHERE ticketId = ? ORDER BY id DESC LIMIT 5"

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute(Tuple.of(ticketId))
            .await()

        return rows.toEntities()
    }

    override suspend fun getByTicketIdAndStartFromId(
        lastMessageId: Long,
        ticketId: Long,
        sqlClient: SqlClient
    ): List<TicketMessage> {
        val query =
            "SELECT id, userId, ticketId, message, `date`, `panel` FROM `${getTablePrefix() + tableName}` WHERE ticketId = ? and id < ? ORDER BY id DESC LIMIT 5"

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute(Tuple.of(ticketId, lastMessageId))
            .await()

        return rows.toEntities()
    }

    override suspend fun getCountByTicketId(
        ticketId: Long,
        sqlClient: SqlClient
    ): Long {
        val query =
            "SELECT COUNT(id) FROM `${getTablePrefix() + tableName}` where ticketId = ?"

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute(Tuple.of(ticketId))
            .await()

        return rows.toList()[0].getLong(0)
    }

    override suspend fun addMessage(
        ticketMessage: TicketMessage,
        sqlClient: SqlClient
    ): Long {
        val query =
            "INSERT INTO `${getTablePrefix() + tableName}` (userId, ticketId, message, `date`, panel) VALUES (?, ?, ?, ?, ?)"

        val rows: RowSet<Row> = sqlClient
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
        sqlClient: SqlClient
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
            "DELETE FROM `${getTablePrefix() + tableName}` WHERE ticketId IN ($selectedTicketsSQLText)"

        sqlClient
            .preparedQuery(query)
            .execute(parameters)
            .await()
    }

    override suspend fun getLastMessageByTicketId(
        ticketId: Long,
        sqlClient: SqlClient
    ): TicketMessage? {
        val query =
            "SELECT id, userId, ticketId, message, `date`, `panel` FROM `${getTablePrefix() + tableName}` WHERE ticketId = ? ORDER BY `date` DESC LIMIT 1"

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute(Tuple.of(ticketId))
            .await()


        if (rows.size() == 0) {
            return null
        }

        val row = rows.toList()[0]

        return row.toEntity()
    }

    override suspend fun existsById(id: Long, sqlClient: SqlClient): Boolean {
        val query = "SELECT COUNT(id) FROM `${getTablePrefix() + tableName}` where `id` = ?"

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute(Tuple.of(id))
            .await()

        return rows.toList()[0].getLong(0) == 1L
    }

    override suspend fun updateUserIdByUserId(userId: Long, newUserId: Long, sqlClient: SqlClient) {
        val query = "UPDATE `${getTablePrefix() + tableName}` SET `userId` = ? WHERE `userId` = ?"

        sqlClient
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    newUserId,
                    userId
                )
            )
            .await()
    }
}