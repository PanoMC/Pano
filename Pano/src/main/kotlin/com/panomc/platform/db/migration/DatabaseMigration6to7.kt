package com.panomc.platform.db.migration

import com.panomc.platform.annotation.Migration
import com.panomc.platform.db.DatabaseMigration
import com.panomc.platform.db.model.Locale
import io.vertx.core.json.JsonArray
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.RowSet
import io.vertx.sqlclient.SqlClient
import io.vertx.sqlclient.Tuple

@Migration
class DatabaseMigration6to7 : DatabaseMigration(6, 7, "Add locale & translation tables.") {
    override val handlers: List<suspend (SqlClient) -> Unit> = listOf(
        addLocaleTable(),
        addTranslationTable()
    )

    private fun addLocaleTable(): suspend (sqlClient: SqlClient) -> Unit =
        { sqlClient: SqlClient ->
            sqlClient
                .query(
                    """
                        CREATE TABLE IF NOT EXISTS `${getTablePrefix()}locale` (
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
                """.trimIndent()
                )
                .execute()
                .coAwait()

            addLocale(
                Locale(
                    code = "en-US",
                    name = "English (US)",
                    dateFnsCode = "en-US",
                    derivatives = listOf()
                ), sqlClient
            )
            addLocale(
                Locale(
                    code = "tr",
                    name = "Türkçe (TR)",
                    dateFnsCode = "tr",
                    derivatives = listOf("tr-tr")
                ), sqlClient
            )
        }

    private fun addTranslationTable(): suspend (sqlClient: SqlClient) -> Unit =
        { sqlClient: SqlClient ->
            sqlClient
                .query(
                    """
                        CREATE TABLE IF NOT EXISTS `${getTablePrefix()}translation` (
                          `id` bigint NOT NULL AUTO_INCREMENT,
                          `localeId` varchar(10) NOT NULL,
                          `type` varchar(255) NOT NULL,
                          `key` text NOT NULL,
                          `value` text NOT NULL,
                          `createdAt` BIGINT(20) NOT NULL,
                          `updatedAt` BIGINT(20) NOT NULL,
                          PRIMARY KEY (`id`)
                        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Locales table.';
                """.trimIndent()
                )
                .execute()
                .coAwait()
        }

    private suspend fun addLocale(locale: Locale, sqlClient: SqlClient) {
        val existsQuery = "SELECT COUNT(id) FROM `${getTablePrefix()}locale` where `code` = ?"

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(existsQuery)
            .execute(
                Tuple.of(
                    locale.code
                )
            )
            .coAwait()

        if (rows.toList()[0].getLong(0) == 1L) {
            return
        }

        val query =
            "INSERT INTO `${getTablePrefix()}locale` (`code`, `name`, `dateFnsCode`, `derivatives`, `definedBy`, `createdAt`, `updatedAt`) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?);" // 7

        sqlClient
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
    }
}