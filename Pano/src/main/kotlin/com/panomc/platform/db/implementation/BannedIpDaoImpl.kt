package com.panomc.platform.db.implementation

import com.panomc.platform.annotation.Dao
import com.panomc.platform.db.dao.BannedIpDao
import com.panomc.platform.db.dao.BannedIpListFilter
import com.panomc.platform.db.model.BannedIp
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
class BannedIpDaoImpl : BannedIpDao() {
    private fun getBannedIpTableName(): String = "`${getTablePrefix() + tableName}`"

    private fun getOffsetQuery(page: Long): String = if (page == 1L) "" else "OFFSET ${(page - 1) * 10}"

    override suspend fun init(sqlClient: SqlClient) {
        sqlClient
            .query(
                """
                            CREATE TABLE IF NOT EXISTS `${getTablePrefix() + tableName}` (
                              `id` bigint NOT NULL AUTO_INCREMENT,
                              `ip` varchar(45) NOT NULL,
                              `reason` varchar(255),
                              `bannedUntil` bigint,
                              `bannedBy` varchar(255),
                              `source` varchar(255),
                              `bannedBySystem` tinyint(1) NOT NULL DEFAULT 0,
                              `createdAt` BIGINT(20) NOT NULL,
                              `updatedAt` BIGINT(20) NOT NULL,
                              PRIMARY KEY (`id`),
                              UNIQUE KEY `uq_banned_ip_ip` (`ip`)
                            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='IP ban list.';
                        """
            )
            .execute()
            .coAwait()
    }

    override suspend fun add(
        bannedIp: BannedIp,
        sqlClient: SqlClient
    ): Long {
        val query =
            "INSERT INTO ${getBannedIpTableName()} (`ip`, `reason`, `bannedUntil`, `bannedBy`, `source`, `bannedBySystem`, `createdAt`, `updatedAt`) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?)"

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    bannedIp.ip,
                    bannedIp.reason,
                    bannedIp.bannedUntil,
                    bannedIp.bannedBy,
                    bannedIp.source,
                    bannedIp.bannedBySystem,
                    bannedIp.createdAt,
                    bannedIp.updatedAt,
                )
            ).coAwait()

        return rows.property(MySQLClient.LAST_INSERTED_ID)
    }

    override suspend fun upsert(
        bannedIp: BannedIp,
        sqlClient: SqlClient
    ) {
        val query = """
            INSERT INTO ${getBannedIpTableName()} (`ip`, `reason`, `bannedUntil`, `bannedBy`, `source`, `bannedBySystem`, `createdAt`, `updatedAt`)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE
                `reason` = VALUES(`reason`),
                `bannedUntil` = VALUES(`bannedUntil`),
                `bannedBy` = VALUES(`bannedBy`),
                `source` = VALUES(`source`),
                `bannedBySystem` = VALUES(`bannedBySystem`),
                `updatedAt` = VALUES(`updatedAt`)
        """.trimIndent()

        sqlClient
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    bannedIp.ip,
                    bannedIp.reason,
                    bannedIp.bannedUntil,
                    bannedIp.bannedBy,
                    bannedIp.source,
                    bannedIp.bannedBySystem,
                    bannedIp.createdAt,
                    bannedIp.updatedAt,
                )
            ).coAwait()
    }

    override suspend fun existsByIp(
        ip: String,
        sqlClient: SqlClient
    ): Boolean {
        val query = "SELECT COUNT(`id`) FROM ${getBannedIpTableName()} WHERE `ip` = ?"

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute(Tuple.of(ip))
            .coAwait()

        return rows.toList()[0].getLong(0) > 0L
    }

    override suspend fun getActiveByIp(
        ip: String,
        sqlClient: SqlClient
    ): BannedIp? {
        val query = """
            SELECT ${fields.toTableQuery()} FROM ${getBannedIpTableName()}
            WHERE `ip` = ?
              AND (`bannedUntil` IS NULL OR `bannedUntil` > ?)
            LIMIT 1
        """.trimIndent()

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute(Tuple.of(ip, System.currentTimeMillis()))
            .coAwait()

        if (rows.size() == 0) {
            return null
        }

        return rows.toList()[0].toEntity()
    }

    override suspend fun getById(
        id: Long,
        sqlClient: SqlClient
    ): BannedIp? {
        val query = "SELECT ${fields.toTableQuery()} FROM ${getBannedIpTableName()} WHERE `id` = ? LIMIT 1"

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute(Tuple.of(id))
            .coAwait()

        if (rows.size() == 0) {
            return null
        }

        return rows.toList()[0].toEntity()
    }

    override suspend fun count(sqlClient: SqlClient): Long {
        val query = "SELECT COUNT(`id`) FROM ${getBannedIpTableName()}"

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute()
            .coAwait()

        return rows.toList()[0].getLong(0)
    }

    override suspend fun countBySearch(
        search: String,
        sqlClient: SqlClient
    ): Long {
        val normalized = search.trim()
        if (normalized.isEmpty()) return count(sqlClient)

        val query = """
            SELECT COUNT(`id`) FROM ${getBannedIpTableName()}
            WHERE LOWER(`ip`) LIKE ?
               OR LOWER(COALESCE(`reason`, '')) LIKE ?
               OR LOWER(COALESCE(`bannedBy`, '')) LIKE ?
        """.trimIndent()

        val like = "%${normalized.lowercase()}%"
        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute(Tuple.of(like, like, like))
            .coAwait()

        return rows.toList()[0].getLong(0)
    }

    override suspend fun getAllByPage(
        page: Long,
        sqlClient: SqlClient
    ): List<BannedIp> {
        val query = """
            SELECT ${fields.toTableQuery()} FROM ${getBannedIpTableName()}
            ORDER BY `updatedAt` DESC, `createdAt` DESC
            LIMIT 10 ${getOffsetQuery(page)}
        """.trimIndent()

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute()
            .coAwait()

        return rows.toEntities()
    }

    override suspend fun getAllByPageAndSearch(
        page: Long,
        search: String,
        sqlClient: SqlClient
    ): List<BannedIp> {
        val normalized = search.trim()
        if (normalized.isEmpty()) return getAllByPage(page, sqlClient)

        val query = """
            SELECT ${fields.toTableQuery()} FROM ${getBannedIpTableName()}
            WHERE LOWER(`ip`) LIKE ?
               OR LOWER(COALESCE(`reason`, '')) LIKE ?
               OR LOWER(COALESCE(`bannedBy`, '')) LIKE ?
            ORDER BY `updatedAt` DESC, `createdAt` DESC
            LIMIT 10 ${getOffsetQuery(page)}
        """.trimIndent()

        val like = "%${normalized.lowercase()}%"
        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute(Tuple.of(like, like, like))
            .coAwait()

        return rows.toEntities()
    }

    private fun listFilterWhereClause(listFilter: BannedIpListFilter): String = when (listFilter) {
        BannedIpListFilter.ACTIVE -> "(`bannedUntil` IS NULL OR `bannedUntil` > ?)"
        BannedIpListFilter.HISTORY -> "(`bannedUntil` IS NOT NULL AND `bannedUntil` <= ?)"
    }

    override suspend fun countByListFilter(
        listFilter: BannedIpListFilter,
        nowMs: Long,
        sqlClient: SqlClient
    ): Long {
        val query = "SELECT COUNT(`id`) FROM ${getBannedIpTableName()} WHERE ${listFilterWhereClause(listFilter)}"
        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute(Tuple.of(nowMs))
            .coAwait()
        return rows.toList()[0].getLong(0)
    }

    override suspend fun countByListFilterAndSearch(
        listFilter: BannedIpListFilter,
        search: String,
        nowMs: Long,
        sqlClient: SqlClient
    ): Long {
        val normalized = search.trim()
        if (normalized.isEmpty()) return countByListFilter(listFilter, nowMs, sqlClient)
        val like = "%${normalized.lowercase()}%"
        val query = """
            SELECT COUNT(`id`) FROM ${getBannedIpTableName()}
            WHERE ${listFilterWhereClause(listFilter)}
              AND (
                LOWER(`ip`) LIKE ?
                OR LOWER(COALESCE(`reason`, '')) LIKE ?
                OR LOWER(COALESCE(`bannedBy`, '')) LIKE ?
              )
        """.trimIndent()
        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute(Tuple.of(nowMs, like, like, like))
            .coAwait()
        return rows.toList()[0].getLong(0)
    }

    override suspend fun getAllByPageAndListFilter(
        page: Long,
        listFilter: BannedIpListFilter,
        nowMs: Long,
        sqlClient: SqlClient
    ): List<BannedIp> {
        val query = """
            SELECT ${fields.toTableQuery()} FROM ${getBannedIpTableName()}
            WHERE ${listFilterWhereClause(listFilter)}
            ORDER BY `updatedAt` DESC, `createdAt` DESC
            LIMIT 10 ${getOffsetQuery(page)}
        """.trimIndent()
        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute(Tuple.of(nowMs))
            .coAwait()
        return rows.toEntities()
    }

    override suspend fun getAllByPageAndListFilterAndSearch(
        page: Long,
        search: String,
        listFilter: BannedIpListFilter,
        nowMs: Long,
        sqlClient: SqlClient
    ): List<BannedIp> {
        val normalized = search.trim()
        if (normalized.isEmpty()) return getAllByPageAndListFilter(page, listFilter, nowMs, sqlClient)
        val like = "%${normalized.lowercase()}%"
        val query = """
            SELECT ${fields.toTableQuery()} FROM ${getBannedIpTableName()}
            WHERE ${listFilterWhereClause(listFilter)}
              AND (
                LOWER(`ip`) LIKE ?
                OR LOWER(COALESCE(`reason`, '')) LIKE ?
                OR LOWER(COALESCE(`bannedBy`, '')) LIKE ?
              )
            ORDER BY `updatedAt` DESC, `createdAt` DESC
            LIMIT 10 ${getOffsetQuery(page)}
        """.trimIndent()
        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute(Tuple.of(nowMs, like, like, like))
            .coAwait()
        return rows.toEntities()
    }

    override suspend fun deleteById(
        id: Long,
        sqlClient: SqlClient
    ) {
        val query = "DELETE FROM ${getBannedIpTableName()} WHERE `id` = ?"

        sqlClient
            .preparedQuery(query)
            .execute(Tuple.of(id))
            .coAwait()
    }
}
