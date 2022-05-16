package com.panomc.platform.db.migration

import com.panomc.platform.annotation.Migration
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.DatabaseMigration
import io.vertx.kotlin.coroutines.await
import io.vertx.sqlclient.SqlConnection

@Suppress("ClassName")
@Migration
class DatabaseMigration_4_5(databaseManager: DatabaseManager) : DatabaseMigration(databaseManager) {
    override val FROM_SCHEME_VERSION = 4
    override val SCHEME_VERSION = 5
    override val SCHEME_VERSION_INFO = "Add new status and date field to panel notifications table."

    override val handlers: List<suspend (sqlConnection: SqlConnection) -> Unit> =
        listOf(
            updatePanelNotificationsTable()
        )

    private fun updatePanelNotificationsTable(): suspend (sqlConnection: SqlConnection) -> Unit =
        { sqlConnection: SqlConnection ->
            sqlConnection
                .query("ALTER TABLE `${getTablePrefix()}panel_notification` ADD date MEDIUMTEXT, ADD status varchar(255);")
                .execute()
                .await()
        }
}