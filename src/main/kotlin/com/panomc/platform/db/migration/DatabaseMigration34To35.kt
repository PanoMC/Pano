package com.panomc.platform.db.migration

import com.panomc.platform.annotation.Migration
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.DatabaseMigration
import io.vertx.kotlin.coroutines.await
import io.vertx.sqlclient.SqlConnection

@Migration
class DatabaseMigration34To35(databaseManager: DatabaseManager) : DatabaseMigration(databaseManager) {
    override val FROM_SCHEME_VERSION = 34
    override val SCHEME_VERSION = 35
    override val SCHEME_VERSION_INFO =
        "Add action & properties fields to notification & panel notification tables."

    override val handlers: List<suspend (sqlConnection: SqlConnection) -> Unit> =
        listOf(
            addActionFieldToNotificationTable(),
            addPropertiesFieldToNotificationTable(),
            addActionFieldToPanelNotificationTable(),
            addPropertiesFieldToPanelNotificationTable()
        )

    private fun addActionFieldToNotificationTable(): suspend (sqlConnection: SqlConnection) -> Unit =
        { sqlConnection: SqlConnection ->
            sqlConnection
                .query("ALTER TABLE `${getTablePrefix()}notification` ADD `action` varchar(255) NOT NULL DEFAULT '';")
                .execute()
                .await()
        }

    private fun addPropertiesFieldToNotificationTable(): suspend (sqlConnection: SqlConnection) -> Unit =
        { sqlConnection: SqlConnection ->
            sqlConnection
                .query("ALTER TABLE `${getTablePrefix()}notification` ADD `properties` mediumtext NOT NULL;")
                .execute()
                .await()
        }

    private fun addActionFieldToPanelNotificationTable(): suspend (sqlConnection: SqlConnection) -> Unit =
        { sqlConnection: SqlConnection ->
            sqlConnection
                .query("ALTER TABLE `${getTablePrefix()}panel_notification` ADD `action` varchar(255) NOT NULL DEFAULT '';")
                .execute()
                .await()
        }

    private fun addPropertiesFieldToPanelNotificationTable(): suspend (sqlConnection: SqlConnection) -> Unit =
        { sqlConnection: SqlConnection ->
            sqlConnection
                .query("ALTER TABLE `${getTablePrefix()}panel_notification` ADD `properties` mediumtext NOT NULL;")
                .execute()
                .await()
        }
}