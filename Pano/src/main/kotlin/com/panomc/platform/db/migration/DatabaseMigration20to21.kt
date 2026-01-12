package com.panomc.platform.db.migration

import com.panomc.platform.annotation.Migration
import com.panomc.platform.db.DatabaseMigration
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.sqlclient.SqlClient

@Migration
class DatabaseMigration20to21 : DatabaseMigration(20, 21, "Add pluginId to panel_activity_log table.") {
    override val handlers: List<suspend (SqlClient) -> Unit> = listOf(
        addPluginIdToPanelActivityLog()
    )

    private fun addPluginIdToPanelActivityLog(): suspend (sqlClient: SqlClient) -> Unit =
        { sqlClient: SqlClient ->
            val prefix = getTablePrefix()
            val query = "ALTER TABLE `${prefix}panel_activity_log` ADD COLUMN `pluginId` varchar(255) AFTER `userId`"

            sqlClient
                .query(query)
                .execute()
                .coAwait()
        }
}
