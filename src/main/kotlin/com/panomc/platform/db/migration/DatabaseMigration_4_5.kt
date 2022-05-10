package com.panomc.platform.db.migration

import com.panomc.platform.annotation.Migration
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.DatabaseMigration
import io.vertx.core.AsyncResult
import io.vertx.sqlclient.SqlConnection

@Suppress("ClassName")
@Migration
class DatabaseMigration_4_5(databaseManager: DatabaseManager) : DatabaseMigration(databaseManager) {
    override val FROM_SCHEME_VERSION = 4
    override val SCHEME_VERSION = 5
    override val SCHEME_VERSION_INFO = "Add new status and date field to panel notifications table."

    override val handlers: List<(sqlConnection: SqlConnection, handler: (asyncResult: AsyncResult<*>) -> Unit) -> Unit> =
        listOf(
            updatePanelNotificationsTable()
        )

    private fun updatePanelNotificationsTable(): (sqlConnection: SqlConnection, handler: (asyncResult: AsyncResult<*>) -> Unit) -> Unit =
        { sqlConnection, handler ->
            sqlConnection
                .query("ALTER TABLE `${getTablePrefix()}panel_notification` ADD date MEDIUMTEXT, ADD status varchar(255);")
                .execute {
                    handler.invoke(it)
                }
        }
}