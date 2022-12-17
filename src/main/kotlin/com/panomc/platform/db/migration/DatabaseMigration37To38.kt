package com.panomc.platform.db.migration

import com.panomc.platform.annotation.Migration
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.DatabaseMigration
import io.vertx.kotlin.coroutines.await
import io.vertx.sqlclient.SqlConnection

@Migration
class DatabaseMigration37To38(databaseManager: DatabaseManager) : DatabaseMigration(databaseManager) {
    override val FROM_SCHEME_VERSION = 37
    override val SCHEME_VERSION = 38
    override val SCHEME_VERSION_INFO = "Add motd, host and port fields to server table."

    override val handlers: List<suspend (sqlConnection: SqlConnection) -> Unit> =
        listOf(
            addMotdFieldToServerTable(),
            addHostFieldToServerTable(),
            addPortFieldToServerTable()
        )

    private fun addMotdFieldToServerTable(): suspend (sqlConnection: SqlConnection) -> Unit =
        { sqlConnection: SqlConnection ->
            sqlConnection
                .query("ALTER TABLE `${getTablePrefix()}server` ADD `motd` text NOT NULL DEFAULT '';")
                .execute()
                .await()
        }

    private fun addHostFieldToServerTable(): suspend (sqlConnection: SqlConnection) -> Unit =
        { sqlConnection: SqlConnection ->
            sqlConnection
                .query("ALTER TABLE `${getTablePrefix()}server` ADD `host` varchar(255) NOT NULL DEFAULT '';")
                .execute()
                .await()
        }

    private fun addPortFieldToServerTable(): suspend (sqlConnection: SqlConnection) -> Unit =
        { sqlConnection: SqlConnection ->
            sqlConnection
                .query("ALTER TABLE `${getTablePrefix()}server` ADD `port` int(5) NOT NULL;")
                .execute()
                .await()
        }
}