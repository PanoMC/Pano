package com.panomc.platform.db.migration

import com.panomc.platform.annotation.Migration
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.DatabaseMigration
import io.vertx.kotlin.coroutines.await
import io.vertx.sqlclient.SqlClient

@Migration
class DatabaseMigration35To36(databaseManager: DatabaseManager) : DatabaseMigration(databaseManager) {
    override val FROM_SCHEME_VERSION = 35
    override val SCHEME_VERSION = 36
    override val SCHEME_VERSION_INFO =
        "Add last_login_date field to user table."

    override val handlers: List<suspend (sqlClient: SqlClient) -> Unit> =
        listOf(
            addLastLoginDateFieldToUserTable(),
        )

    private fun addLastLoginDateFieldToUserTable(): suspend (sqlClient: SqlClient) -> Unit =
        { sqlClient: SqlClient ->
            sqlClient
                .query("ALTER TABLE `${getTablePrefix()}user` ADD `last_login_date` BIGINT(20) NOT NULL DEFAULT 0;")
                .execute()
                .await()
        }
}