package com.panomc.platform.db.migration

import com.panomc.platform.annotation.Migration
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.DatabaseMigration
import io.vertx.kotlin.coroutines.await
import io.vertx.sqlclient.SqlClient

@Migration
class DatabaseMigration15To16(databaseManager: DatabaseManager) : DatabaseMigration(databaseManager) {
    override val FROM_SCHEME_VERSION = 15
    override val SCHEME_VERSION = 16
    override val SCHEME_VERSION_INFO = "Add last_update field to ticket table."

    override val handlers: List<suspend (sqlClient: SqlClient) -> Unit> =
        listOf(
            addPanelFieldToTicketMessageTable()
        )

    private fun addPanelFieldToTicketMessageTable(): suspend (sqlClient: SqlClient) -> Unit =
        { sqlClient: SqlClient ->
            sqlClient
                .query("ALTER TABLE `${getTablePrefix()}ticket` ADD `last_update` MEDIUMTEXT NOT NULL DEFAULT '0';")
                .execute()
                .await()
        }
}