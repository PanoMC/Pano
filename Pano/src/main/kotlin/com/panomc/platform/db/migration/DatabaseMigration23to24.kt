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
class DatabaseMigration23to24 : DatabaseMigration(23, 24, "Add Russian (ru) locale support.") {
    override val handlers: List<suspend (SqlClient) -> Unit> = listOf(
        { sqlClient ->
            addLocale(
                Locale(
                    code = "ru",
                    name = "Русский (RU)",
                    dateFnsCode = "ru",
                    derivatives = listOf()
                ),
                sqlClient
            )
        }
    )

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

        if (rows.toList()[0].getLong(0) >= 1L) {
            return
        }

        val query =
            "INSERT INTO `${getTablePrefix()}locale` (`code`, `name`, `dateFnsCode`, `derivatives`, `definedBy`, `createdAt`, `updatedAt`) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?);"

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
