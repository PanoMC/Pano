package com.panomc.platform.db.migration

import com.panomc.platform.annotation.Migration
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.DatabaseMigration
import com.panomc.platform.db.model.TicketCategory
import com.panomc.platform.util.TextUtil
import io.vertx.kotlin.coroutines.await
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.RowSet
import io.vertx.sqlclient.SqlConnection
import io.vertx.sqlclient.Tuple

@Suppress("ClassName")
@Migration
class DatabaseMigration_21_22(databaseManager: DatabaseManager) : DatabaseMigration(databaseManager) {
    override val FROM_SCHEME_VERSION = 21
    override val SCHEME_VERSION = 22
    override val SCHEME_VERSION_INFO =
        "Add URL column to ticket category table."

    override val handlers: List<suspend (sqlConnection: SqlConnection) -> Unit> =
        listOf(
            addUrlColumnToTicketTable(),
            convertTicketCategoryTitlesToUrl()
        )

    private fun addUrlColumnToTicketTable(): suspend (sqlConnection: SqlConnection) -> Unit =
        { sqlConnection: SqlConnection ->
            sqlConnection
                .query("ALTER TABLE `${getTablePrefix()}ticket_category` ADD `url` mediumtext NOT NULL DEFAULT '';")
                .execute()
                .await()
        }

    private fun convertTicketCategoryTitlesToUrl(): suspend (sqlConnection: SqlConnection) -> Unit =
        { sqlConnection: SqlConnection ->
            val rows: RowSet<Row> = sqlConnection
                .preparedQuery("SELECT id, title FROM `${getTablePrefix()}ticket_category`")
                .execute()
                .await()

            val categories = mutableListOf<TicketCategory>()

            rows.forEach { row ->
                categories.add(
                    TicketCategory(
                        row.getLong(0),
                        row.getString(1)
                    )
                )
            }

            categories.forEach { category ->
                val query = "UPDATE `${getTablePrefix()}ticket_category` SET url = ? WHERE id = ?"

                val url = TextUtil.convertStringToUrl(category.title)

                sqlConnection
                    .preparedQuery(query)
                    .execute(
                        Tuple.of(
                            url,
                            category.id
                        )
                    ).await()
            }
        }
}