package com.panomc.platform.db.migration

import com.panomc.platform.annotation.Migration
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.DatabaseMigration
import io.vertx.kotlin.coroutines.await
import io.vertx.sqlclient.SqlConnection

@Suppress("ClassName")
@Migration
class DatabaseMigration_14_15(databaseManager: DatabaseManager) : DatabaseMigration(databaseManager) {
    override val FROM_SCHEME_VERSION = 14
    override val SCHEME_VERSION = 15
    override val SCHEME_VERSION_INFO = "Add panel field to ticket_message table."

    override val handlers: List<suspend (sqlConnection: SqlConnection) -> Unit> =
        listOf(
            addPanelFieldToTicketMessageTable()
        )

    private fun addPanelFieldToTicketMessageTable(): suspend (sqlConnection: SqlConnection) -> Unit =
        { sqlConnection: SqlConnection ->
            sqlConnection
                .query("ALTER TABLE `${getTablePrefix()}ticket_message` ADD `panel` int(1) NOT NULL DEFAULT 0;")
                .execute()
                .await()
        }
}