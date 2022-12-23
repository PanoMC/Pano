package com.panomc.platform.db.migration

import com.panomc.platform.annotation.Migration
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.DatabaseMigration
import io.vertx.kotlin.coroutines.await
import io.vertx.sqlclient.SqlConnection

@Migration
class DatabaseMigration39To40(databaseManager: DatabaseManager) : DatabaseMigration(databaseManager) {
    override val FROM_SCHEME_VERSION = 39
    override val SCHEME_VERSION = 40
    override val SCHEME_VERSION_INFO =
        "Delete action column from panel notification and notification tables & rename type_id column."

    override val handlers: List<suspend (sqlConnection: SqlConnection) -> Unit> =
        listOf(
            renameNotificationTableTypeIdColumn(),
            renamePanelNotificationTableTypeIdColumn(),
            deleteActionColumnFromNotificationTable(),
            deleteActionColumnFromPanelNotificationTable()
        )

    private fun renameNotificationTableTypeIdColumn(): suspend (sqlConnection: SqlConnection) -> Unit =
        { sqlConnection: SqlConnection ->
            sqlConnection
                .query("ALTER TABLE `${getTablePrefix()}notification` RENAME COLUMN `type_id` TO `type`;")
                .execute()
                .await()
        }

    private fun deleteActionColumnFromNotificationTable(): suspend (sqlConnection: SqlConnection) -> Unit =
        { sqlConnection: SqlConnection ->
            sqlConnection
                .query("ALTER TABLE `${getTablePrefix()}notification` DROP COLUMN `action`;")
                .execute()
                .await()
        }

    private fun renamePanelNotificationTableTypeIdColumn(): suspend (sqlConnection: SqlConnection) -> Unit =
        { sqlConnection: SqlConnection ->
            sqlConnection
                .query("ALTER TABLE `${getTablePrefix()}panel_notification` RENAME COLUMN `type_id` TO `type`;")
                .execute()
                .await()
        }

    private fun deleteActionColumnFromPanelNotificationTable(): suspend (sqlConnection: SqlConnection) -> Unit =
        { sqlConnection: SqlConnection ->
            sqlConnection
                .query("ALTER TABLE `${getTablePrefix()}panel_notification` DROP COLUMN `action`;")
                .execute()
                .await()
        }
}