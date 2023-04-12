package com.panomc.platform.db.migration

import com.panomc.platform.annotation.Migration
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.DatabaseMigration
import io.vertx.kotlin.coroutines.await
import io.vertx.sqlclient.SqlClient

@Migration
class DatabaseMigration48To49(databaseManager: DatabaseManager) : DatabaseMigration(databaseManager) {
    override val FROM_SCHEME_VERSION = 48
    override val SCHEME_VERSION = 49
    override val SCHEME_VERSION_INFO = "Add added_time & accepted_time fields to server table."

    override val handlers: List<suspend (sqlClient: SqlClient) -> Unit> =
        listOf(
            addAddedTimeFieldToServerTable(),
            addAcceptedTimeFieldToServerTable()
        )

    private fun addAddedTimeFieldToServerTable(): suspend (sqlClient: SqlClient) -> Unit =
        { sqlClient: SqlClient ->
            sqlClient
                .query("ALTER TABLE `${getTablePrefix()}server` ADD `added_time` bigint NOT NULL;")
                .execute()
                .await()
        }

    private fun addAcceptedTimeFieldToServerTable(): suspend (sqlClient: SqlClient) -> Unit =
        { sqlClient: SqlClient ->
            sqlClient
                .query("ALTER TABLE `${getTablePrefix()}server` ADD `accepted_time` bigint NOT NULL;")
                .execute()
                .await()
        }

}