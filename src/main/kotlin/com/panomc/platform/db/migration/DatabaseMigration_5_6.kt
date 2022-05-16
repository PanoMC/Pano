package com.panomc.platform.db.migration

import com.panomc.platform.annotation.Migration
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.DatabaseMigration
import io.vertx.kotlin.coroutines.await
import io.vertx.sqlclient.SqlConnection

@Suppress("ClassName")
@Migration
class DatabaseMigration_5_6(databaseManager: DatabaseManager) : DatabaseMigration(databaseManager) {
    override val FROM_SCHEME_VERSION = 5
    override val SCHEME_VERSION = 6
    override val SCHEME_VERSION_INFO = "Drop post, post_category, ticket and ticket_category."

    override val handlers: List<suspend (sqlConnection: SqlConnection) -> Unit> =
        listOf(
            dropPanelPostTable(),
            dropPanelPostCategoryTable(),
            dropPanelTicketTable(),
            dropPanelTicketCategoryTable()
        )

    private fun dropPanelPostTable(): suspend (sqlConnection: SqlConnection) -> Unit =
        { sqlConnection: SqlConnection ->
            sqlConnection
                .query("DROP TABLE IF EXISTS `${getTablePrefix()}post`;")
                .execute()
                .await()
        }

    private fun dropPanelPostCategoryTable(): suspend (sqlConnection: SqlConnection) -> Unit =
        { sqlConnection: SqlConnection ->
            sqlConnection
                .query("DROP TABLE IF EXISTS `${getTablePrefix()}post_category`;")
                .execute()
                .await()
        }

    private fun dropPanelTicketTable(): suspend (sqlConnection: SqlConnection) -> Unit =
        { sqlConnection: SqlConnection ->
            sqlConnection
                .query(" DROP TABLE IF EXISTS `${getTablePrefix()}ticket`;")
                .execute()
                .await()
        }

    private fun dropPanelTicketCategoryTable(): suspend (sqlConnection: SqlConnection) -> Unit =
        { sqlConnection: SqlConnection ->
            sqlConnection
                .query("DROP TABLE IF EXISTS `${getTablePrefix()}ticket_category`;")
                .execute()
                .await()
        }
}