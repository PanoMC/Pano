package com.panomc.platform.db.migration

import com.panomc.platform.annotation.Migration
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.DatabaseMigration
import io.vertx.kotlin.coroutines.await
import io.vertx.sqlclient.SqlClient

@Migration
class DatabaseMigration22To23(databaseManager: DatabaseManager) : DatabaseMigration(databaseManager) {
    override val FROM_SCHEME_VERSION = 22
    override val SCHEME_VERSION = 23
    override val SCHEME_VERSION_INFO =
        "Rename post text colum name."

    override val handlers: List<suspend (sqlClient: SqlClient) -> Unit> =
        listOf(
            renamePostTextColumnName(),
        )

    private fun renamePostTextColumnName(): suspend (sqlClient: SqlClient) -> Unit =
        { sqlClient: SqlClient ->
            sqlClient
                .query("ALTER TABLE `${getTablePrefix()}post` RENAME COLUMN `post` TO `text`;")
                .execute()
                .await()
        }
}