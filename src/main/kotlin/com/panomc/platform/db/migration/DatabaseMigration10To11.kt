package com.panomc.platform.db.migration

import com.panomc.platform.annotation.Migration
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.DatabaseMigration
import io.vertx.kotlin.coroutines.await
import io.vertx.sqlclient.SqlConnection

@Migration
class DatabaseMigration10To11(databaseManager: DatabaseManager) : DatabaseMigration(databaseManager) {
    override val FROM_SCHEME_VERSION = 10
    override val SCHEME_VERSION = 11
    override val SCHEME_VERSION_INFO = "Add views field to post table."

    override val handlers: List<suspend (sqlConnection: SqlConnection) -> Unit> =
        listOf(
            addViewsFieldToPostTable()
        )

    private fun addViewsFieldToPostTable(): suspend (sqlConnection: SqlConnection) -> Unit =
        { sqlConnection: SqlConnection ->
            sqlConnection
                .query("ALTER TABLE `${getTablePrefix()}post` ADD `views` MEDIUMTEXT;")
                .execute()
                .await()
        }
}