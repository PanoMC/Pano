package com.panomc.platform.db.implementation

import com.panomc.platform.annotation.Dao
import com.panomc.platform.db.dao.PanelActivityLogDao
import com.panomc.platform.db.model.PanelActivityLog
import com.panomc.platform.server.ServerActivityNotifier
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
                              `pluginId` varchar(255),
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
            "INSERT INTO `${getTablePrefix() + tableName}` (`userId`, `pluginId`, `type`, `details`, `createdAt`, `updatedAt`) " +
                    "VALUES (?, ?, ?, ?, ?, ?)"

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    panelActivityLog.userId,
                    panelActivityLog.pluginId,
                    panelActivityLog.type,
                    panelActivityLog.details.toString(),
                    panelActivityLog.createdAt,
                    panelActivityLog.updatedAt,
                )
            ).coAwait()

        // The one choke point for the live "Recent activity" feed: every server-scoped entry,
        // whoever wrote it, is announced once it is in the table.
        ServerActivityNotifier.onLogAdded(panelActivityLog)

        return rows.property(MySQLClient.LAST_INSERTED_ID)
    }

    /**
     * One server's entries, filtered in SQL on the `details` JSON.
     *
     * The trade-off, spelled out because it is the interesting part: `panel_activity_log` has no
     * `serverId` column and deliberately does not grow one. `details` is a free-form JSON document
     * that plugins write into as well, adding a column would mean a migration that backfills by
     * parsing that same JSON, and every writer would then have to remember to fill both. So the
     * filter is two `LIKE`s against the encoded JSON.
     *
     * Two, not one, because `%"serverId":12%` also matches server 120 — the terminator is what
     * makes the match exact, and `JsonObject.encode()` never puts a space after the colon, so the
     * pair covers every row Pano writes. It is a full scan of the table, which is acceptable for
     * a screen an admin opens occasionally and is bounded by `limit`; if it ever stops being
     * acceptable, the fix is a generated column with an index, not a wider filter here.
     */
    override suspend fun byServerId(
        serverId: Long,
        types: List<String>,
        limit: Int,
        beforeId: Long?,
        sqlClient: SqlClient
    ): List<PanelActivityLog> {
        if (types.isEmpty()) {
            return emptyList()
        }

        val typePlaceholders = types.joinToString(", ") { "?" }
        val cursor = if (beforeId == null) "" else "AND `id` < ? "

        val query = "SELECT ${fields.toTableQuery()} FROM `${getTablePrefix() + tableName}` " +
                "WHERE `type` IN ($typePlaceholders) " +
                "AND (`details` LIKE ? OR `details` LIKE ?) " +
                cursor +
                "ORDER BY `createdAt` DESC, `id` DESC LIMIT ?"

        val parameters = mutableListOf<Any>()

        parameters.addAll(types)
        parameters.add("%\"serverId\":$serverId,%")
        parameters.add("%\"serverId\":$serverId}%")

        beforeId?.let { parameters.add(it) }

        parameters.add(limit.coerceIn(1, MAX_PAGE_SIZE))

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute(Tuple.from(parameters))
            .coAwait()

        return rows.toEntities()
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

    override suspend fun byUserId(
        userId: Long,
        sqlClient: SqlClient
    ): List<PanelActivityLog> {
        val query =
            "SELECT ${fields.toTableQuery()} FROM `${getTablePrefix() + tableName}` WHERE `userId` = ? ORDER BY `createdAt` DESC, `id` DESC"

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

    override suspend fun getAll(
        sqlClient: SqlClient
    ): List<PanelActivityLog> {
        val query =
            "SELECT ${fields.toTableQuery()} FROM `${getTablePrefix() + tableName}` ORDER BY `createdAt` DESC, `id` DESC"

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

    override suspend fun deleteByUserId(
        userId: Long,
        sqlClient: SqlClient
    ) {
        val query =
            "DELETE FROM `${getTablePrefix() + tableName}` WHERE `userId` = ?"

        sqlClient
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    userId
                )
            )
            .coAwait()
    }

    override suspend fun countByTypeSince(type: String, since: Long, sqlClient: SqlClient): Long {
        val query = "SELECT COUNT(id) FROM `${getTablePrefix() + tableName}` " +
                "WHERE `type` = ? AND `createdAt` >= ?"

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute(Tuple.of(type, since))
            .coAwait()

        return rows.toList()[0].getLong(0)
    }

    companion object {
        /** Hard ceiling on one page, whatever the caller asks for. */
        const val MAX_PAGE_SIZE = 200
    }
}
