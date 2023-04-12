package com.panomc.platform.db.migration

import com.panomc.platform.annotation.Migration
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.DatabaseMigration
import io.vertx.kotlin.coroutines.await
import io.vertx.sqlclient.SqlClient

@Migration
class DatabaseMigration46To47(databaseManager: DatabaseManager) : DatabaseMigration(databaseManager) {
    override val FROM_SCHEME_VERSION = 46
    override val SCHEME_VERSION = 47
    override val SCHEME_VERSION_INFO = "Add pending_email field to user table."

    override val handlers: List<suspend (sqlClient: SqlClient) -> Unit> =
        listOf(
            addPendingEmailFieldToUserTable()
        )

    private fun addPendingEmailFieldToUserTable(): suspend (sqlClient: SqlClient) -> Unit =
        { sqlClient: SqlClient ->
            sqlClient
                .query("ALTER TABLE `${getTablePrefix()}user` ADD `pending_email` varchar(255) NOT NULL DEFAULT '';")
                .execute()
                .await()
        }
}