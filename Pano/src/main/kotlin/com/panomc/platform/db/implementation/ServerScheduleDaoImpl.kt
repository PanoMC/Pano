package com.panomc.platform.db.implementation

import com.panomc.platform.annotation.Dao
import com.panomc.platform.db.dao.ServerScheduleDao
import com.panomc.platform.db.model.ServerSchedule
import com.panomc.platform.server.schedule.ScheduleRunStatus
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.mysqlclient.MySQLClient
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.RowSet
import io.vertx.sqlclient.SqlClient
import io.vertx.sqlclient.Tuple

@Dao
class ServerScheduleDaoImpl : ServerScheduleDao() {

    override suspend fun init(sqlClient: SqlClient) {
        sqlClient
            .query(
                """
                            CREATE TABLE IF NOT EXISTS `${getTablePrefix() + tableName}` (
                              `id` bigint NOT NULL AUTO_INCREMENT,
                              `uuid` varchar(36) NOT NULL,
                              `serverId` bigint NOT NULL,
                              `name` varchar(255) NOT NULL,
                              `cron` varchar(100) NOT NULL,
                              `timezone` varchar(64) NOT NULL,
                              `enabled` tinyint(1) NOT NULL DEFAULT 1,
                              `warnMinutes` int NOT NULL DEFAULT 0,
                              `lastRunAt` bigint DEFAULT NULL,
                              `lastStatus` varchar(16) DEFAULT NULL,
                              `lastError` text DEFAULT NULL,
                              `nextRunAt` bigint DEFAULT NULL,
                              `createdBy` bigint NOT NULL,
                              `createdAt` bigint NOT NULL,
                              `updatedAt` bigint NOT NULL,
                              PRIMARY KEY (`id`),
                              UNIQUE KEY `idx_server_schedule_uuid` (`uuid`),
                              KEY `idx_server_schedule_server` (`serverId`)
                            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Recurring jobs on a server.';
                        """
            )
            .execute()
            .coAwait()
    }

    override suspend fun add(serverSchedule: ServerSchedule, sqlClient: SqlClient): Long {
        val query =
            "INSERT INTO `${getTablePrefix() + tableName}` " +
                    "(`uuid`, `serverId`, `name`, `cron`, `timezone`, `enabled`, `warnMinutes`, `lastRunAt`, " +
                    "`lastStatus`, `lastError`, `nextRunAt`, `createdBy`, `createdAt`, `updatedAt`) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    serverSchedule.uuid,
                    serverSchedule.serverId,
                    serverSchedule.name,
                    serverSchedule.cron,
                    serverSchedule.timezone,
                    serverSchedule.enabled,
                    serverSchedule.warnMinutes,
                    serverSchedule.lastRunAt,
                    serverSchedule.lastStatus?.name,
                    serverSchedule.lastError,
                    serverSchedule.nextRunAt,
                    serverSchedule.createdBy,
                    serverSchedule.createdAt,
                    serverSchedule.updatedAt
                )
            )
            .coAwait()

        return rows.property(MySQLClient.LAST_INSERTED_ID)
    }

    override suspend fun getById(id: Long, sqlClient: SqlClient): ServerSchedule? {
        val query = "SELECT ${fields.toTableQuery()} FROM `${getTablePrefix() + tableName}` WHERE `id` = ?"

        val rows: RowSet<Row> = sqlClient.preparedQuery(query).execute(Tuple.of(id)).coAwait()

        if (rows.size() == 0) {
            return null
        }

        return rows.toList()[0].toEntity()
    }

    override suspend fun getByUuid(uuid: String, sqlClient: SqlClient): ServerSchedule? {
        val query = "SELECT ${fields.toTableQuery()} FROM `${getTablePrefix() + tableName}` WHERE `uuid` = ?"

        val rows: RowSet<Row> = sqlClient.preparedQuery(query).execute(Tuple.of(uuid)).coAwait()

        if (rows.size() == 0) {
            return null
        }

        return rows.toList()[0].toEntity()
    }

    override suspend fun getAllByServerId(serverId: Long, sqlClient: SqlClient): List<ServerSchedule> {
        val query = "SELECT ${fields.toTableQuery()} FROM `${getTablePrefix() + tableName}` " +
                "WHERE `serverId` = ? ORDER BY `id` ASC"

        val rows: RowSet<Row> = sqlClient.preparedQuery(query).execute(Tuple.of(serverId)).coAwait()

        return rows.toEntities()
    }

    override suspend fun getAllEnabled(sqlClient: SqlClient): List<ServerSchedule> {
        val query = "SELECT ${fields.toTableQuery()} FROM `${getTablePrefix() + tableName}` " +
                "WHERE `enabled` = 1 ORDER BY `id` ASC"

        val rows: RowSet<Row> = sqlClient.preparedQuery(query).execute().coAwait()

        return rows.toEntities()
    }

    override suspend fun update(serverSchedule: ServerSchedule, sqlClient: SqlClient) {
        val query = "UPDATE `${getTablePrefix() + tableName}` SET `name` = ?, `cron` = ?, `timezone` = ?, " +
                "`enabled` = ?, `warnMinutes` = ?, `nextRunAt` = ?, `updatedAt` = ? WHERE `id` = ?"

        sqlClient
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    serverSchedule.name,
                    serverSchedule.cron,
                    serverSchedule.timezone,
                    serverSchedule.enabled,
                    serverSchedule.warnMinutes,
                    serverSchedule.nextRunAt,
                    serverSchedule.updatedAt,
                    serverSchedule.id
                )
            )
            .coAwait()
    }

    override suspend fun updateEnabledById(id: Long, enabled: Boolean, nextRunAt: Long?, sqlClient: SqlClient) {
        val query = "UPDATE `${getTablePrefix() + tableName}` SET `enabled` = ?, `nextRunAt` = ?, `updatedAt` = ? " +
                "WHERE `id` = ?"

        sqlClient
            .preparedQuery(query)
            .execute(Tuple.of(enabled, nextRunAt, System.currentTimeMillis(), id))
            .coAwait()
    }

    override suspend fun updateRunResultById(
        id: Long,
        lastRunAt: Long,
        lastStatus: ScheduleRunStatus,
        lastError: String?,
        nextRunAt: Long?,
        sqlClient: SqlClient
    ) {
        val query = "UPDATE `${getTablePrefix() + tableName}` SET `lastRunAt` = ?, `lastStatus` = ?, " +
                "`lastError` = ?, `nextRunAt` = ?, `updatedAt` = ? WHERE `id` = ?"

        sqlClient
            .preparedQuery(query)
            .execute(
                Tuple.of(lastRunAt, lastStatus.name, lastError, nextRunAt, System.currentTimeMillis(), id)
            )
            .coAwait()
    }

    override suspend fun deleteById(id: Long, sqlClient: SqlClient) {
        val query = "DELETE FROM `${getTablePrefix() + tableName}` WHERE `id` = ?"

        sqlClient.preparedQuery(query).execute(Tuple.of(id)).coAwait()
    }

    override suspend fun deleteByServerId(serverId: Long, sqlClient: SqlClient) {
        val query = "DELETE FROM `${getTablePrefix() + tableName}` WHERE `serverId` = ?"

        sqlClient.preparedQuery(query).execute(Tuple.of(serverId)).coAwait()
    }
}
