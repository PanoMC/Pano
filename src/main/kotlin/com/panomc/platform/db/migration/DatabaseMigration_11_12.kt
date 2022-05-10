package com.panomc.platform.db.migration

import com.panomc.platform.annotation.Migration
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.DatabaseMigration
import io.vertx.core.AsyncResult
import io.vertx.sqlclient.SqlConnection

@Suppress("ClassName")
@Migration
class DatabaseMigration_11_12(databaseManager: DatabaseManager) : DatabaseMigration(databaseManager) {
    override val FROM_SCHEME_VERSION = 11
    override val SCHEME_VERSION = 12
    override val SCHEME_VERSION_INFO = "Add register_date field to user table."

    override val handlers: List<(sqlConnection: SqlConnection, handler: (asyncResult: AsyncResult<*>) -> Unit) -> Unit> =
        listOf(
            addRegisterDateFieldToUserTable()
        )

    private fun addRegisterDateFieldToUserTable(): (sqlConnection: SqlConnection, handler: (asyncResult: AsyncResult<*>) -> Unit) -> Unit =
        { sqlConnection, handler ->
            sqlConnection
                .query("ALTER TABLE `${getTablePrefix()}user` ADD `register_date` MEDIUMTEXT;")
                .execute {
                    handler.invoke(it)
                }
        }
}