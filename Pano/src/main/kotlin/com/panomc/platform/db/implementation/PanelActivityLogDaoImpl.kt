package com.panomc.platform.db.implementation

import com.panomc.platform.annotation.Dao
import com.panomc.platform.db.dao.PanelActivityLogDao
import com.panomc.platform.db.model.PanelActivityLog
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.mysqlclient.MySQLClient
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.RowSet
import io.vertx.sqlclient.SqlClient
import io.vertx.sqlclient.Tuple
import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.context.annotation.Lazy
import org.springframework.context.annotation.Scope

@Dao
@Lazy
@Scope(value = ConfigurableBeanFactory.SCOPE_SINGLETON)
class PanelActivityLogDaoImpl : PanelActivityLogDao() {

    override suspend fun init(sqlClient: SqlClient) {
        sqlClient
            .query(
                """
                            CREATE TABLE IF NOT EXISTS `${getTablePrefix() + tableName}` (
                              `id` bigint NOT NULL AUTO_INCREMENT,
                              `userId` bigint,
                              `type` varchar(255) NOT NULL,
                              `details` mediumtext NOT NULL,
                              `createdAt` BIGINT(20) NOT NULL,
                              `updatedAt` BIGINT(20) NOT NULL,
                              PRIMARY KEY (`id`)
                            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Panel activity log table.';
                        """
            )
            .execute()
            .coAwait()
    }

    override suspend fun add(
        panelActivityLog: PanelActivityLog,
        sqlClient: SqlClient
    ): Long {
        val query =
            "INSERT INTO `${getTablePrefix() + tableName}` (`userId`, `type`, `details`, `createdAt`, `updatedAt`) " +
                    "VALUES (?, ?, ?, ?, ?)"

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    panelActivityLog.userId,
                    panelActivityLog.type,
                    panelActivityLog.details.toString(),
                    panelActivityLog.createdAt,
                    panelActivityLog.updatedAt,
                )
            ).coAwait()

        return rows.property(MySQLClient.LAST_INSERTED_ID)
    }

    override suspend fun byUserId(
        userId: Long,
        page: Long,
        sqlClient: SqlClient
    ): List<PanelActivityLog> {
        val query =
            "SELECT ${fields.toTableQuery()} FROM `${getTablePrefix() + tableName}` WHERE `userId` = ? ORDER BY `createdAt` DESC, `id` DESC LIMIT 10 ${if (page == 1L) "" else "OFFSET ${(page - 1) * 10}"}"

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    userId
                )
            ).coAwait()

        return rows.toEntities()
    }

    override suspend fun getAll(
        page: Long,
        sqlClient: SqlClient
    ): List<PanelActivityLog> {
        val query =
            "SELECT ${fields.toTableQuery()} FROM `${getTablePrefix() + tableName}` ORDER BY `createdAt` DESC, `id` DESC LIMIT 10 ${if (page == 1L) "" else "OFFSET ${(page - 1) * 10}"}"

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute()
            .coAwait()

        return rows.toEntities()
    }

    override suspend fun count(
        userId: Long,
        sqlClient: SqlClient
    ): Long {
        val query = "SELECT COUNT(*) FROM `${getTablePrefix() + tableName}` WHERE `userId` = ?"

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute(Tuple.of(userId))
            .coAwait()

        return rows.toList()[0].getLong(0)
    }

    override suspend fun count(
        sqlClient: SqlClient
    ): Long {
        val query = "SELECT COUNT(*) FROM `${getTablePrefix() + tableName}`"

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute()
            .coAwait()

        return rows.toList()[0].getLong(0)
    }
}