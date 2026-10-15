package com.panomc.platform.db.implementation

import com.panomc.platform.annotation.Dao
import com.panomc.platform.db.dao.FrontendKeyDao
import com.panomc.platform.db.model.FrontendKey
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.mysqlclient.MySQLClient
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.RowSet
import io.vertx.sqlclient.SqlClient
import io.vertx.sqlclient.Tuple

@Dao
class FrontendKeyDaoImpl : FrontendKeyDao() {

    override suspend fun init(sqlClient: SqlClient) {
        sqlClient
            .query(createTableQuery(getTablePrefix() + tableName))
            .execute()
            .coAwait()
    }

    override suspend fun add(frontendKey: FrontendKey, sqlClient: SqlClient): Long {
        val query = "INSERT INTO `${getTablePrefix() + tableName}` " +
                "(`name`, `keyHash`, `keyHint`, `createdBy`, `createdAt`, `lastUsedAt`) VALUES (?, ?, ?, ?, ?, ?)"

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    frontendKey.name,
                    frontendKey.keyHash,
                    frontendKey.keyHint,
                    frontendKey.createdBy,
                    frontendKey.createdAt,
                    frontendKey.lastUsedAt
                )
            )
            .coAwait()

        return rows.property(MySQLClient.LAST_INSERTED_ID)
    }

    override suspend fun getAll(sqlClient: SqlClient): List<FrontendKey> {
        val query = "SELECT ${fields.toTableQuery()} FROM `${getTablePrefix() + tableName}` ORDER BY `id` ASC"

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute()
            .coAwait()

        return rows.toEntities()
    }

    override suspend fun getById(id: Long, sqlClient: SqlClient): FrontendKey? {
        val query = "SELECT ${fields.toTableQuery()} FROM `${getTablePrefix() + tableName}` WHERE `id` = ?"

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute(Tuple.of(id))
            .coAwait()

        return rows.firstOrNull()?.toEntity()
    }

    override suspend fun count(sqlClient: SqlClient): Int {
        val query = "SELECT COUNT(*) FROM `${getTablePrefix() + tableName}`"

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute()
            .coAwait()

        return rows.first().getInteger(0)
    }

    override suspend fun deleteById(id: Long, sqlClient: SqlClient): Boolean {
        val query = "DELETE FROM `${getTablePrefix() + tableName}` WHERE `id` = ?"

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute(Tuple.of(id))
            .coAwait()

        return rows.rowCount() > 0
    }

    override suspend fun updateLastUsedAt(id: Long, lastUsedAt: Long, sqlClient: SqlClient) {
        val query = "UPDATE `${getTablePrefix() + tableName}` SET `lastUsedAt` = ? WHERE `id` = ?"

        sqlClient.preparedQuery(query).execute(Tuple.of(lastUsedAt, id)).coAwait()
    }

    companion object {
        /** Shared with `DatabaseMigration56to57`, so a fresh install and an upgrade get one table. */
        fun createTableQuery(table: String) = """
            CREATE TABLE IF NOT EXISTS `$table` (
              `id` bigint NOT NULL AUTO_INCREMENT,
              `name` varchar(64) NOT NULL,
              `keyHash` varchar(64) NOT NULL,
              `keyHint` varchar(8) NOT NULL,
              `createdBy` bigint NOT NULL,
              `createdAt` bigint NOT NULL,
              `lastUsedAt` bigint NULL,
              PRIMARY KEY (`id`),
              UNIQUE KEY `idx_frontend_key_hash` (`keyHash`)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Keys that identify a front-end server to the API.';
        """.trimIndent()
    }
}
