package com.panomc.platform.db.migration

import com.panomc.platform.annotation.Migration
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.DatabaseMigration
import io.vertx.kotlin.coroutines.await
import io.vertx.sqlclient.SqlConnection

@Migration
class DatabaseMigration44To45(databaseManager: DatabaseManager) : DatabaseMigration(databaseManager) {
    override val FROM_SCHEME_VERSION = 44
    override val SCHEME_VERSION = 45
    override val SCHEME_VERSION_INFO = "Add can_create_ticket field to user table."

    override val handlers: List<suspend (sqlConnection: SqlConnection) -> Unit> =
        listOf(
            addPanelFieldToTicketMessageTable()
        )

    private fun addPanelFieldToTicketMessageTable(): suspend (sqlConnection: SqlConnection) -> Unit =
        { sqlConnection: SqlConnection ->
            sqlConnection
                .query("ALTER TABLE `${getTablePrefix()}user` ADD `can_create_ticket` int(1) NOT NULL DEFAULT 1;")
                .execute()
                .await()
        }
}