package com.panomc.platform.db.migration

import com.panomc.platform.annotation.Migration
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.DatabaseMigration
import io.vertx.kotlin.coroutines.await
import io.vertx.sqlclient.SqlConnection

@Migration
class DatabaseMigration6To7(databaseManager: DatabaseManager) : DatabaseMigration(databaseManager) {
    override val FROM_SCHEME_VERSION = 6
    override val SCHEME_VERSION = 7
    override val SCHEME_VERSION_INFO = "Change date field in table panel_notifications to LONG type."

    override val handlers: List<suspend (sqlConnection: SqlConnection) -> Unit> =
        listOf(
            changeField()
        )

    private fun changeField(): suspend (sqlConnection: SqlConnection) -> Unit =
        { sqlConnection: SqlConnection ->
            sqlConnection
                .query("ALTER TABLE `${getTablePrefix()}panel_notification` MODIFY `date` MEDIUMTEXT;")
                .execute()
                .await()
        }
}