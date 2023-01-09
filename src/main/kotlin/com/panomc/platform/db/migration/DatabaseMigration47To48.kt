package com.panomc.platform.db.migration

import com.panomc.platform.annotation.Migration
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.DatabaseMigration
import io.vertx.kotlin.coroutines.await
import io.vertx.sqlclient.SqlConnection

@Migration
class DatabaseMigration47To48(databaseManager: DatabaseManager) : DatabaseMigration(databaseManager) {
    override val FROM_SCHEME_VERSION = 47
    override val SCHEME_VERSION = 48
    override val SCHEME_VERSION_INFO = "Add start_time & stop_time field to server table."

    override val handlers: List<suspend (sqlConnection: SqlConnection) -> Unit> =
        listOf(
            addStartTimeFieldToServerTable(),
            addStopTimeFieldToServerTable(),
        )

    private fun addStartTimeFieldToServerTable(): suspend (sqlConnection: SqlConnection) -> Unit =
        { sqlConnection: SqlConnection ->
            sqlConnection
                .query("ALTER TABLE `${getTablePrefix()}server` ADD `start_time` bigint NOT NULL;")
                .execute()
                .await()
        }

    private fun addStopTimeFieldToServerTable(): suspend (sqlConnection: SqlConnection) -> Unit =
        { sqlConnection: SqlConnection ->
            sqlConnection
                .query("ALTER TABLE `${getTablePrefix()}server` ADD `stop_time` bigint NOT NULL;")
                .execute()
                .await()
        }

}