package com.panomc.platform.db.migration

import com.panomc.platform.annotation.Migration
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.DatabaseMigration
import io.vertx.core.AsyncResult
import io.vertx.sqlclient.SqlConnection

@Suppress("ClassName")
@Migration
class DatabaseMigration_9_10(databaseManager: DatabaseManager) : DatabaseMigration(databaseManager) {
    override val FROM_SCHEME_VERSION = 9
    override val SCHEME_VERSION = 10
    override val SCHEME_VERSION_INFO = "Update post, ticket and few other tables to be better."

    override val handlers: List<(sqlConnection: SqlConnection, handler: (asyncResult: AsyncResult<*>) -> Unit) -> Unit> =
        listOf(
            updatePostTableDateColumn(),
            updateTicketTableDateColumn(),
            updatePostTableTitleColumn(),
            updatePostTableMoveDateColumn(),
            updatePostTableCategoryIDColumn(),
            updateTicketTableTicketCategoryIDColumn(),
            updateTicketTableTitleColumn(),
            updateTicketCategoryTableTitleColumn(),
            updatePostCategoryTableTitleColumn(),
            updateTokenTableCreatedTimeColumn()
        )

    private fun updatePostTableDateColumn(): (sqlConnection: SqlConnection, handler: (asyncResult: AsyncResult<*>) -> Unit) -> Unit =
        { sqlConnection, handler ->
            sqlConnection
                .query("ALTER TABLE `${getTablePrefix()}post` MODIFY `date` MEDIUMTEXT;")
                .execute {
                    handler.invoke(it)
                }
        }

    private fun updatePostTableMoveDateColumn(): (sqlConnection: SqlConnection, handler: (asyncResult: AsyncResult<*>) -> Unit) -> Unit =
        { sqlConnection, handler ->
            sqlConnection
                .query(" ALTER TABLE `${getTablePrefix()}post` MODIFY `move_date` MEDIUMTEXT;")
                .execute {
                    handler.invoke(it)
                }
        }

    private fun updatePostTableTitleColumn(): (sqlConnection: SqlConnection, handler: (asyncResult: AsyncResult<*>) -> Unit) -> Unit =
        { sqlConnection, handler ->
            sqlConnection
                .query("ALTER TABLE `${getTablePrefix()}post` MODIFY `title` MEDIUMTEXT;")
                .execute {
                    handler.invoke(it)
                }
        }

    private fun updatePostTableCategoryIDColumn(): (sqlConnection: SqlConnection, handler: (asyncResult: AsyncResult<*>) -> Unit) -> Unit =
        { sqlConnection, handler ->
            sqlConnection
                .query("ALTER TABLE `${getTablePrefix()}post` MODIFY `category_id` int(11);")
                .execute {
                    handler.invoke(it)
                }
        }

    private fun updatePostCategoryTableTitleColumn(): (sqlConnection: SqlConnection, handler: (asyncResult: AsyncResult<*>) -> Unit) -> Unit =
        { sqlConnection, handler ->
            sqlConnection
                .query("ALTER TABLE `${getTablePrefix()}post_category` MODIFY `title` MEDIUMTEXT;")
                .execute {
                    handler.invoke(it)
                }
        }

    private fun updateTicketTableDateColumn(): (sqlConnection: SqlConnection, handler: (asyncResult: AsyncResult<*>) -> Unit) -> Unit =
        { sqlConnection, handler ->
            sqlConnection
                .query("ALTER TABLE `${getTablePrefix()}ticket` MODIFY `date` MEDIUMTEXT;")
                .execute {
                    handler.invoke(it)
                }
        }

    private fun updateTicketTableTicketCategoryIDColumn(): (sqlConnection: SqlConnection, handler: (asyncResult: AsyncResult<*>) -> Unit) -> Unit =
        { sqlConnection, handler ->
            sqlConnection
                .query("ALTER TABLE `${getTablePrefix()}ticket` CHANGE `ticket_category_id` `category_id` int(11);")
                .execute {
                    handler.invoke(it)
                }
        }

    private fun updateTicketTableTitleColumn(): (sqlConnection: SqlConnection, handler: (asyncResult: AsyncResult<*>) -> Unit) -> Unit =
        { sqlConnection, handler ->
            sqlConnection
                .query("ALTER TABLE `${getTablePrefix()}ticket` MODIFY `title` MEDIUMTEXT;")
                .execute {
                    handler.invoke(it)
                }
        }

    private fun updateTicketCategoryTableTitleColumn(): (sqlConnection: SqlConnection, handler: (asyncResult: AsyncResult<*>) -> Unit) -> Unit =
        { sqlConnection, handler ->
            sqlConnection
                .query("ALTER TABLE `${getTablePrefix()}ticket_category` MODIFY `title` MEDIUMTEXT;")
                .execute {
                    handler.invoke(it)
                }
        }

    private fun updateTokenTableCreatedTimeColumn(): (sqlConnection: SqlConnection, handler: (asyncResult: AsyncResult<*>) -> Unit) -> Unit =
        { sqlConnection, handler ->
            sqlConnection
                .query("ALTER TABLE `${getTablePrefix()}token` MODIFY `created_time` MEDIUMTEXT;")
                .execute {
                    handler.invoke(it)
                }
        }
}