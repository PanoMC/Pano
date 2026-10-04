package com.panomc.platform.db.implementation

import com.panomc.platform.annotation.Dao
import com.panomc.platform.db.dao.ServerScheduleTaskDao
import com.panomc.platform.db.model.ServerScheduleTask
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.mysqlclient.MySQLClient
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.RowSet
import io.vertx.sqlclient.SqlClient
import io.vertx.sqlclient.Tuple

@Dao
class ServerScheduleTaskDaoImpl : ServerScheduleTaskDao() {

    override suspend fun init(sqlClient: SqlClient) {
        sqlClient
            .query(
                """
                            CREATE TABLE IF NOT EXISTS `${getTablePrefix() + tableName}` (
                              `id` bigint NOT NULL AUTO_INCREMENT,
                              `scheduleId` bigint NOT NULL,
                              `position` int NOT NULL DEFAULT 0,
                              `kind` varchar(16) NOT NULL,
                              `payload` text NOT NULL,
                              PRIMARY KEY (`id`),
                              KEY `idx_server_schedule_task_schedule` (`scheduleId`)
                            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Steps of a server schedule.';
                        """
            )
            .execute()
            .coAwait()
    }

    override suspend fun add(serverScheduleTask: ServerScheduleTask, sqlClient: SqlClient): Long {
        val query = "INSERT INTO `${getTablePrefix() + tableName}` " +
                "(`scheduleId`, `position`, `kind`, `payload`) VALUES (?, ?, ?, ?)"

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    serverScheduleTask.scheduleId,
                    serverScheduleTask.position,
                    serverScheduleTask.kind.name,
                    serverScheduleTask.payload
                )
            )
            .coAwait()

        return rows.property(MySQLClient.LAST_INSERTED_ID)
    }

    override suspend fun getAllByScheduleId(scheduleId: Long, sqlClient: SqlClient): List<ServerScheduleTask> {
        val query = "SELECT ${fields.toTableQuery()} FROM `${getTablePrefix() + tableName}` " +
                "WHERE `scheduleId` = ? ORDER BY `position` ASC, `id` ASC"

        val rows: RowSet<Row> = sqlClient.preparedQuery(query).execute(Tuple.of(scheduleId)).coAwait()

        return rows.toEntities()
    }

    override suspend fun getAllByScheduleIds(
        scheduleIds: List<Long>,
        sqlClient: SqlClient
    ): List<ServerScheduleTask> {
        if (scheduleIds.isEmpty()) {
            return emptyList()
        }

        val placeholders = scheduleIds.joinToString(", ") { "?" }

        val query = "SELECT ${fields.toTableQuery()} FROM `${getTablePrefix() + tableName}` " +
                "WHERE `scheduleId` IN ($placeholders) ORDER BY `scheduleId` ASC, `position` ASC, `id` ASC"

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute(Tuple.from(scheduleIds.toTypedArray()))
            .coAwait()

        return rows.toEntities()
    }

    override suspend fun deleteByScheduleId(scheduleId: Long, sqlClient: SqlClient) {
        val query = "DELETE FROM `${getTablePrefix() + tableName}` WHERE `scheduleId` = ?"

        sqlClient.preparedQuery(query).execute(Tuple.of(scheduleId)).coAwait()
    }
}
