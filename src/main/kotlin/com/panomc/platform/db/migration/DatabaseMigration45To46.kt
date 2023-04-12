package com.panomc.platform.db.migration

import com.panomc.platform.annotation.Migration
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.DatabaseMigration
import io.vertx.kotlin.coroutines.await
import io.vertx.sqlclient.SqlClient

@Migration
class DatabaseMigration45To46(databaseManager: DatabaseManager) : DatabaseMigration(databaseManager) {
    override val FROM_SCHEME_VERSION = 45
    override val SCHEME_VERSION = 46
    override val SCHEME_VERSION_INFO = "Add start_date field to token table."

    override val handlers: List<suspend (sqlClient: SqlClient) -> Unit> =
        listOf(
            addStartDateFieldToTokenTable()
        )

    private fun addStartDateFieldToTokenTable(): suspend (sqlClient: SqlClient) -> Unit =
        { sqlClient: SqlClient ->
            sqlClient
                .query("ALTER TABLE `${getTablePrefix()}token` ADD `start_date` bigint NOT NULL;")
                .execute()
                .await()
        }
}