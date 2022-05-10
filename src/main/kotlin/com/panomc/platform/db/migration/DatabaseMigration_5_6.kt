package com.panomc.platform.db.migration

import com.panomc.platform.annotation.Migration
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.DatabaseMigration
import io.vertx.core.AsyncResult
import io.vertx.sqlclient.SqlConnection

@Suppress("ClassName")
@Migration
class DatabaseMigration_5_6(databaseManager: DatabaseManager) : DatabaseMigration(databaseManager) {
    override val FROM_SCHEME_VERSION = 5
    override val SCHEME_VERSION = 6
    override val SCHEME_VERSION_INFO = "Drop post, post_category, ticket and ticket_category."

    override val handlers: List<(sqlConnection: SqlConnection, handler: (asyncResult: AsyncResult<*>) -> Unit) -> Unit> =
        listOf(
            dropPanelPostTable(),
            dropPanelPostCategoryTable(),
            dropPanelTicketTable(),
            dropPanelTicketCategoryTable()
        )

    private fun dropPanelPostTable(): (sqlConnection: SqlConnection, handler: (asyncResult: AsyncResult<*>) -> Unit) -> Unit =
        { sqlConnection, handler ->
            sqlConnection
                .query("DROP TABLE IF EXISTS `${getTablePrefix()}post`;")
                .execute {
                    handler.invoke(it)
                }
        }

    private fun dropPanelPostCategoryTable(): (sqlConnection: SqlConnection, handler: (asyncResult: AsyncResult<*>) -> Unit) -> Unit =
        { sqlConnection, handler ->
            sqlConnection
                .query("DROP TABLE IF EXISTS `${getTablePrefix()}post_category`;")
                .execute {
                    handler.invoke(it)
                }
        }

    private fun dropPanelTicketTable(): (sqlConnection: SqlConnection, handler: (asyncResult: AsyncResult<*>) -> Unit) -> Unit =
        { sqlConnection, handler ->
            sqlConnection
                .query(" DROP TABLE IF EXISTS `${getTablePrefix()}ticket`;")
                .execute {
                    handler.invoke(it)
                }
        }

    private fun dropPanelTicketCategoryTable(): (sqlConnection: SqlConnection, handler: (asyncResult: AsyncResult<*>) -> Unit) -> Unit =
        { sqlConnection, handler ->
            sqlConnection
                .query("DROP TABLE IF EXISTS `${getTablePrefix()}ticket_category`;")
                .execute {
                    handler.invoke(it)
                }
        }
}