package com.panomc.platform.db.implementation

import com.panomc.platform.annotation.Dao
import com.panomc.platform.db.dao.TicketCategoryFieldSetDao
import com.panomc.platform.db.dao.TicketFieldSetDao
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.sqlclient.SqlClient
import io.vertx.sqlclient.Tuple

@Dao
class TicketCategoryFieldSetDaoImpl : TicketCategoryFieldSetDao() {
    private fun table() = "`${getTablePrefix() + tableName}`"

    override suspend fun init(sqlClient: SqlClient) {
        sqlClient.query(createTableQuery(getTablePrefix() + tableName)).execute().coAwait()
    }

    override suspend fun getByCategoryId(categoryId: Long, sqlClient: SqlClient): String? =
        sqlClient.preparedQuery("SELECT `fields` FROM ${table()} WHERE `categoryId` = ?")
            .execute(Tuple.of(categoryId)).coAwait()
            .firstOrNull()?.getString(0)

    override suspend fun getByCategoryIds(categoryIds: Collection<Long>, sqlClient: SqlClient): Map<Long, String> {
        if (categoryIds.isEmpty()) return emptyMap()

        val marks = categoryIds.joinToString(", ") { "?" }

        return sqlClient.preparedQuery("SELECT `categoryId`, `fields` FROM ${table()} WHERE `categoryId` IN ($marks)")
            .execute(Tuple.from(categoryIds.toList())).coAwait()
            .associate { it.getLong(0) to it.getString(1) }
    }

    override suspend fun set(categoryId: Long, fields: String, sqlClient: SqlClient) {
        if (fields.trim() == "[]") {
            deleteByCategoryId(categoryId, sqlClient)

            return
        }

        sqlClient.preparedQuery(
            "INSERT INTO ${table()} (`categoryId`, `fields`, `updatedAt`) VALUES (?, ?, ?) " +
                    "ON DUPLICATE KEY UPDATE `fields` = VALUES(`fields`), `updatedAt` = VALUES(`updatedAt`)"
        ).execute(Tuple.of(categoryId, fields, System.currentTimeMillis())).coAwait()
    }

    override suspend fun deleteByCategoryId(categoryId: Long, sqlClient: SqlClient) {
        sqlClient.preparedQuery("DELETE FROM ${table()} WHERE `categoryId` = ?").execute(Tuple.of(categoryId)).coAwait()
    }

    override suspend fun uninstall(sqlClient: SqlClient) {
        sqlClient.query("DROP TABLE IF EXISTS ${table()}").execute().coAwait()
    }

    companion object {
        /** Shared with `DatabaseMigration68to69`, so a fresh install and an upgrade get one table. */
        fun createTableQuery(table: String) = """
            CREATE TABLE IF NOT EXISTS `$table` (
              `categoryId` bigint NOT NULL,
              `fields` mediumtext NOT NULL,
              `updatedAt` bigint NOT NULL,
              PRIMARY KEY (`categoryId`)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Custom fields of ticket categories.';
        """.trimIndent()
    }
}

@Dao
class TicketFieldSetDaoImpl : TicketFieldSetDao() {
    private fun table() = "`${getTablePrefix() + tableName}`"

    override suspend fun init(sqlClient: SqlClient) {
        sqlClient.query(createTableQuery(getTablePrefix() + tableName)).execute().coAwait()
    }

    override suspend fun getByTicketId(ticketId: Long, sqlClient: SqlClient): String? =
        sqlClient.preparedQuery("SELECT `fields` FROM ${table()} WHERE `ticketId` = ?")
            .execute(Tuple.of(ticketId)).coAwait()
            .firstOrNull()?.getString(0)

    override suspend fun set(ticketId: Long, fields: String, searchText: String, sqlClient: SqlClient) {
        sqlClient.preparedQuery(
            "INSERT INTO ${table()} (`ticketId`, `fields`, `searchText`, `updatedAt`) VALUES (?, ?, ?, ?) " +
                    "ON DUPLICATE KEY UPDATE `fields` = VALUES(`fields`), `searchText` = VALUES(`searchText`), `updatedAt` = VALUES(`updatedAt`)"
        ).execute(Tuple.of(ticketId, fields, searchText, System.currentTimeMillis())).coAwait()
    }

    override suspend fun deleteByTicketIds(ticketIds: Collection<Long>, sqlClient: SqlClient) {
        if (ticketIds.isEmpty()) return

        val marks = ticketIds.joinToString(", ") { "?" }

        sqlClient.preparedQuery("DELETE FROM ${table()} WHERE `ticketId` IN ($marks)")
            .execute(Tuple.from(ticketIds.toList())).coAwait()
    }

    override suspend fun uninstall(sqlClient: SqlClient) {
        sqlClient.query("DROP TABLE IF EXISTS ${table()}").execute().coAwait()
    }

    companion object {
        /** Shared with `DatabaseMigration68to69`. `searchText` is what the panel's ticket search looks in. */
        fun createTableQuery(table: String) = """
            CREATE TABLE IF NOT EXISTS `$table` (
              `ticketId` bigint NOT NULL,
              `fields` mediumtext NOT NULL,
              `searchText` text NOT NULL,
              `updatedAt` bigint NOT NULL,
              PRIMARY KEY (`ticketId`)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Custom field values of tickets.';
        """.trimIndent()
    }
}
