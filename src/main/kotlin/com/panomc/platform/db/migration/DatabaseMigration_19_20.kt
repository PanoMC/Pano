package com.panomc.platform.db.migration

import com.panomc.platform.annotation.Migration
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.DatabaseMigration
import io.vertx.core.AsyncResult
import io.vertx.sqlclient.SqlConnection

@Suppress("ClassName")
@Migration
class DatabaseMigration_19_20(databaseManager: DatabaseManager) : DatabaseMigration(databaseManager) {
    override val FROM_SCHEME_VERSION = 19
    override val SCHEME_VERSION = 20
    override val SCHEME_VERSION_INFO =
        "Convert dates from string to BigInt."

    override val handlers: List<(sqlConnection: SqlConnection, handler: (asyncResult: AsyncResult<*>) -> Unit) -> Unit> =
        listOf(
            updatePanelNotificationTableDateColumn(),
            updatePostTableDateColumn(),
            updatePostTableMoveDateColumn(),
            updatePostTableViewsColumn(),
            updateTicketTableDateColumn(),
            updateTicketTableLastUpdateColumn(),
            updateTicketMessageTableDateColumn(),
            updateTokenTableCreatedTimeColumn(),
            updateUserTableRegisterDateColumn()
        )

    private fun updatePanelNotificationTableDateColumn(): (sqlConnection: SqlConnection, handler: (asyncResult: AsyncResult<*>) -> Unit) -> Unit =
        { sqlConnection, handler ->
            sqlConnection
                .query("ALTER TABLE `${getTablePrefix()}panel_notification` MODIFY `date` BIGINT(20);")
                .execute {
                    handler.invoke(it)
                }
        }

    private fun updatePostTableDateColumn(): (sqlConnection: SqlConnection, handler: (asyncResult: AsyncResult<*>) -> Unit) -> Unit =
        { sqlConnection, handler ->
            sqlConnection
                .query("ALTER TABLE `${getTablePrefix()}post` MODIFY `date` BIGINT(20);")
                .execute {
                    handler.invoke(it)
                }
        }

    private fun updatePostTableMoveDateColumn(): (sqlConnection: SqlConnection, handler: (asyncResult: AsyncResult<*>) -> Unit) -> Unit =
        { sqlConnection, handler ->
            sqlConnection
                .query("ALTER TABLE `${getTablePrefix()}post` MODIFY `move_date` BIGINT(20);")
                .execute {
                    handler.invoke(it)
                }
        }

    private fun updatePostTableViewsColumn(): (sqlConnection: SqlConnection, handler: (asyncResult: AsyncResult<*>) -> Unit) -> Unit =
        { sqlConnection, handler ->
            sqlConnection
                .query("ALTER TABLE `${getTablePrefix()}post` MODIFY `views` BIGINT(20);")
                .execute {
                    handler.invoke(it)
                }
        }

    private fun updateTicketTableDateColumn(): (sqlConnection: SqlConnection, handler: (asyncResult: AsyncResult<*>) -> Unit) -> Unit =
        { sqlConnection, handler ->
            sqlConnection
                .query("ALTER TABLE `${getTablePrefix()}ticket` MODIFY `date` BIGINT(20);")
                .execute {
                    handler.invoke(it)
                }
        }

    private fun updateTicketTableLastUpdateColumn(): (sqlConnection: SqlConnection, handler: (asyncResult: AsyncResult<*>) -> Unit) -> Unit =
        { sqlConnection, handler ->
            sqlConnection
                .query("ALTER TABLE `${getTablePrefix()}ticket` MODIFY `last_update` BIGINT(20);")
                .execute {
                    handler.invoke(it)
                }
        }

    private fun updateTicketMessageTableDateColumn(): (sqlConnection: SqlConnection, handler: (asyncResult: AsyncResult<*>) -> Unit) -> Unit =
        { sqlConnection, handler ->
            sqlConnection
                .query("ALTER TABLE `${getTablePrefix()}ticket_message` MODIFY `date` BIGINT(20);")
                .execute {
                    handler.invoke(it)
                }
        }

    private fun updateTokenTableCreatedTimeColumn(): (sqlConnection: SqlConnection, handler: (asyncResult: AsyncResult<*>) -> Unit) -> Unit =
        { sqlConnection, handler ->
            sqlConnection
                .query("ALTER TABLE `${getTablePrefix()}token` MODIFY `created_time` BIGINT(20);")
                .execute {
                    handler.invoke(it)
                }
        }

    private fun updateUserTableRegisterDateColumn(): (sqlConnection: SqlConnection, handler: (asyncResult: AsyncResult<*>) -> Unit) -> Unit =
        { sqlConnection, handler ->
            sqlConnection
                .query("ALTER TABLE `${getTablePrefix()}user` MODIFY `register_date` BIGINT(20);")
                .execute {
                    handler.invoke(it)
                }
        }

}