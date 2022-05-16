package com.panomc.platform.db.migration

import com.panomc.platform.annotation.Migration
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.DatabaseMigration
import io.vertx.kotlin.coroutines.await
import io.vertx.sqlclient.SqlConnection

@Suppress("ClassName")
@Migration
class DatabaseMigration_11_12(databaseManager: DatabaseManager) : DatabaseMigration(databaseManager) {
    override val FROM_SCHEME_VERSION = 11
    override val SCHEME_VERSION = 12
    override val SCHEME_VERSION_INFO = "Add register_date field to user table."

    override val handlers: List<suspend (sqlConnection: SqlConnection) -> Unit> =
        listOf(
            addRegisterDateFieldToUserTable()
        )

    private fun addRegisterDateFieldToUserTable(): suspend (sqlConnection: SqlConnection) -> Unit =
        { sqlConnection: SqlConnection ->
            sqlConnection
                .query("ALTER TABLE `${getTablePrefix()}user` ADD `register_date` MEDIUMTEXT;")
                .execute()
                .await()
        }
}