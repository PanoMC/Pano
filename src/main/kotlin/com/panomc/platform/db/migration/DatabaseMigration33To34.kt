package com.panomc.platform.db.migration

import com.panomc.platform.annotation.Migration
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.DatabaseMigration
import io.vertx.kotlin.coroutines.await
import io.vertx.sqlclient.SqlClient

@Migration
class DatabaseMigration33To34(databaseManager: DatabaseManager) : DatabaseMigration(databaseManager) {
    override val FROM_SCHEME_VERSION = 33
    override val SCHEME_VERSION = 34
    override val SCHEME_VERSION_INFO =
        "Rename type_ID to type_id in panel_notification table."

    override val handlers: List<suspend (sqlClient: SqlClient) -> Unit> =
        listOf(
            renamePanelNotificationTypeIdColumnName()
        )

    private fun renamePanelNotificationTypeIdColumnName(): suspend (sqlClient: SqlClient) -> Unit =
        { sqlClient: SqlClient ->
            sqlClient
                .query("ALTER TABLE `${getTablePrefix()}panel_notification` RENAME COLUMN `type_ID` TO `type_id`;")
                .execute()
                .await()
        }
}