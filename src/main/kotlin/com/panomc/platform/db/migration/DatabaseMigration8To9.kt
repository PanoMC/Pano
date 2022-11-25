package com.panomc.platform.db.migration

import com.panomc.platform.annotation.Migration
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.DatabaseMigration
import io.vertx.kotlin.coroutines.await
import io.vertx.sqlclient.SqlConnection
import io.vertx.sqlclient.Tuple

@Migration
class DatabaseMigration8To9(databaseManager: DatabaseManager) : DatabaseMigration(databaseManager) {
    override val FROM_SCHEME_VERSION = 8
    override val SCHEME_VERSION = 9
    override val SCHEME_VERSION_INFO = "Delete platformCode system property."

    override val handlers: List<suspend (sqlConnection: SqlConnection) -> Unit> =
        listOf(
            deletePlatformCode()
        )

    private fun deletePlatformCode(): suspend (sqlConnection: SqlConnection) -> Unit =
        { sqlConnection: SqlConnection ->
            sqlConnection
                .preparedQuery("DELETE FROM ${getTablePrefix()}system_property WHERE `option` = ?")
                .execute(Tuple.of("platformCode"))
                .await()
        }
}