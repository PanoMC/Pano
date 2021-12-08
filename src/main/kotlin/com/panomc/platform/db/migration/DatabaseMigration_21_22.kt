package com.panomc.platform.db.migration

import com.panomc.platform.db.DatabaseMigration
import com.panomc.platform.db.model.TicketCategory
import com.panomc.platform.util.TextUtil
import io.vertx.core.AsyncResult
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.RowSet
import io.vertx.sqlclient.SqlConnection
import io.vertx.sqlclient.Tuple

@Suppress("ClassName")
class DatabaseMigration_21_22 : DatabaseMigration() {
    override val FROM_SCHEME_VERSION = 21
    override val SCHEME_VERSION = 22
    override val SCHEME_VERSION_INFO =
        "Add URL column to ticket category table."

    override val handlers: List<(sqlConnection: SqlConnection, handler: (asyncResult: AsyncResult<*>) -> Unit) -> Unit> =
        listOf(
            addURLColumnToTicketTable(),
            convertTicketCategoryTitlesToURL()
        )

    private fun addURLColumnToTicketTable(): (sqlConnection: SqlConnection, handler: (asyncResult: AsyncResult<*>) -> Unit) -> Unit =
        { sqlConnection, handler ->
            sqlConnection
                .query("ALTER TABLE `${getTablePrefix()}ticket_category` ADD `url` mediumtext NOT NULL DEFAULT '';")
                .execute {
                    handler.invoke(it)
                }
        }

    private fun convertTicketCategoryTitlesToURL(): (sqlConnection: SqlConnection, handler: (asyncResult: AsyncResult<*>) -> Unit) -> Unit =
        { sqlConnection, handler ->
            sqlConnection
                .preparedQuery("SELECT id, title FROM `${getTablePrefix()}ticket_category`")
                .execute { queryResult ->
                    val rows: RowSet<Row> = queryResult.result()
                    val categories = mutableListOf<TicketCategory>()

                    rows.forEach { row ->
                        categories.add(
                            TicketCategory(
                                row.getInteger(0),
                                row.getString(1)
                            )
                        )
                    }

                    fun localHandler(category: TicketCategory) =
                        { invokeHandler: (AsyncResult<*>) -> Unit ->
                            val query = "UPDATE `${getTablePrefix()}ticket_category` SET url = ? WHERE id = ?"

                            val url = TextUtil.convertStringToURL(category.title)

                            sqlConnection
                                .preparedQuery(query)
                                .execute(
                                    Tuple.of(
                                        url,
                                        category.id
                                    )
                                ) { queryResult ->
                                    invokeHandler.invoke(queryResult)
                                }
                        }

                    val categoryHandlers = categories.map { localHandler(it) }

                    var currentIndex = 0

                    fun invoke() {
                        val invokeHandler: (AsyncResult<*>) -> Unit = {
                            when {
                                it.failed() -> handler.invoke(it)
                                currentIndex == categoryHandlers.lastIndex -> handler.invoke(it)
                                else -> {
                                    currentIndex++

                                    invoke()
                                }
                            }
                        }

                        if (currentIndex <= categoryHandlers.lastIndex)
                            categoryHandlers[currentIndex].invoke(invokeHandler)
                    }

                    invoke()
                }
        }
}