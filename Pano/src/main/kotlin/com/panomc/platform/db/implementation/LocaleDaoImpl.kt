package com.panomc.platform.db.implementation

import com.panomc.platform.annotation.Dao
import com.panomc.platform.db.dao.LocaleDao
import com.panomc.platform.db.model.Locale
import io.vertx.core.json.JsonArray
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.mysqlclient.MySQLClient
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.RowSet
import io.vertx.sqlclient.SqlClient
import io.vertx.sqlclient.Tuple

@Dao
class LocaleDaoImpl : LocaleDao() {

    override suspend fun init(sqlClient: SqlClient) {
        sqlClient
            .query(
                """
                        CREATE TABLE IF NOT EXISTS `${getTablePrefix() + tableName}` (
                          `id` bigint NOT NULL AUTO_INCREMENT,
                          `code` varchar(10) NOT NULL,
                          `name` varchar(255) NOT NULL,
                          `dateFnsCode` varchar(255) NOT NULL,
                          `derivatives` text NOT NULL,
                          `definedBy` varchar(255) NOT NULL,
                          `createdAt` BIGINT(20) NOT NULL,
                          `updatedAt` BIGINT(20) NOT NULL,
                          PRIMARY KEY (`id`)
                        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Locales table.';
                        """
            )
            .execute()
            .coAwait()

        add(
            Locale(
                code = "en-US",
                name = "English (US)",
                dateFnsCode = "en-US",
                derivatives = listOf()
            ), sqlClient
        )
        add(
            Locale(
                code = "tr",
                name = "Türkçe (TR)",
                dateFnsCode = "tr",
                derivatives = listOf("tr-tr")
            ), sqlClient
        )
    }

    override suspend fun add(locale: Locale, sqlClient: SqlClient): Long {
        val query =
            "INSERT INTO `${getTablePrefix() + tableName}` (`code`, `name`, `dateFnsCode`, `derivatives`, `definedBy`, `createdAt`, `updatedAt`) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?);" // 7

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    locale.code,
                    locale.name,
                    locale.dateFnsCode,
                    JsonArray(locale.derivatives).encode(),
                    locale.definedBy.name,
                    locale.createdAt,
                    locale.updatedAt,
                )
            )
            .coAwait()

        return rows.property(MySQLClient.LAST_INSERTED_ID)
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

    override suspend fun getAll(
        sqlClient: SqlClient
    ): List<Locale> {
        val query =
            "SELECT ${fields.toTableQuery()} FROM `${getTablePrefix() + tableName}` ORDER BY `createdAt` DESC, `id` DESC"

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute()
            .coAwait()

        return rows.toEntities()
    }

    override suspend fun byId(
        id: Long,
        sqlClient: SqlClient
    ): Locale? {
        val query =
            "SELECT ${fields.toTableQuery()} FROM `${getTablePrefix() + tableName}` WHERE `id` = ? ORDER BY `createdAt` DESC, `id` DESC LIMIT 5"

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    id
                )
            ).coAwait()

        if (rows.size() == 0) {
            return null
        }

        val row = rows.toList()[0]

        return row.toEntity()
    }

    override suspend fun existsById(
        id: Long,
        sqlClient: SqlClient
    ): Boolean {
        val query = "SELECT COUNT(id) FROM `${getTablePrefix() + tableName}` where `id` = ?"

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    id
                )
            )
            .coAwait()

        return rows.toList()[0].getLong(0) == 1L
    }

    override suspend fun deleteById(
        id: Long,
        sqlClient: SqlClient
    ) {
        val query =
            "DELETE FROM `${getTablePrefix() + tableName}` WHERE `id` = ?"

        sqlClient
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    id
                )
            ).coAwait()
    }

    override suspend fun update(
        locale: Locale,
        sqlClient: SqlClient
    ) {
        val query =
            "UPDATE `${getTablePrefix() + tableName}` SET `code` = ?, `name` = ?, `dateFnsCode` = ?, `derivatives` = ? WHERE `id` = ?"

        sqlClient
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    locale.code,
                    locale.name,
                    locale.dateFnsCode,
                    JsonArray(locale.derivatives).encode(),
                    locale.id
                )
            )
            .coAwait()
    }

    override suspend fun getIdByCode(
        code: String,
        sqlClient: SqlClient
    ): Long? {
        val query = "SELECT `id` FROM `${getTablePrefix() + tableName}` where `code` = ?"

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    code
                )
            )
            .coAwait()

        return rows.toList()[0].getLong(0)
    }

    override suspend fun existsByCode(
        code: String,
        sqlClient: SqlClient
    ): Boolean {
        val query = "SELECT COUNT(id) FROM `${getTablePrefix() + tableName}` where `code` = ?"

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    code
                )
            )
            .coAwait()

        return rows.toList()[0].getLong(0) == 1L
    }
}