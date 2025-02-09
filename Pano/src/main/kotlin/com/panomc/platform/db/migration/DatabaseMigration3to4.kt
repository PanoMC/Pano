package com.panomc.platform.db.migration

import com.panomc.platform.annotation.Migration
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.DatabaseMigration
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.sqlclient.SqlClient

@Migration
class DatabaseMigration3to4(databaseManager: DatabaseManager) : DatabaseMigration(databaseManager) {
    override val FROM_SCHEME_VERSION = 3
    override val SCHEME_VERSION = 4
    override val SCHEME_VERSION_INFO = "Delete all panel activity logs because broken."

    override val handlers: List<suspend (SqlClient) -> Unit> = listOf(
        deleteAllPanelActivityLogs()
    )

    private fun deleteAllPanelActivityLogs(): suspend (sqlClient: SqlClient) -> Unit =
        { sqlClient: SqlClient ->
            val query =
                "DELETE FROM `${getTablePrefix()}panel_activity_log`"

            sqlClient
                .preparedQuery(query)
                .execute().coAwait()
        }

}