package com.panomc.platform.db.migration

import com.panomc.platform.annotation.Migration
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.DatabaseMigration
import io.vertx.kotlin.coroutines.await
import io.vertx.sqlclient.SqlClient
import io.vertx.sqlclient.Tuple

@Migration
class DatabaseMigration38To39(databaseManager: DatabaseManager) : DatabaseMigration(databaseManager) {
    override val FROM_SCHEME_VERSION = 38
    override val SCHEME_VERSION = 39
    override val SCHEME_VERSION_INFO = "Add main server option to system property table."

    override val handlers: List<suspend (sqlClient: SqlClient) -> Unit> =
        listOf(
            addMainServerOptionToSystemPropertyTable()
        )

    private fun addMainServerOptionToSystemPropertyTable(): suspend (sqlClient: SqlClient) -> Unit =
        { sqlClient: SqlClient ->
            sqlClient
                .preparedQuery("INSERT INTO ${getTablePrefix()}system_property (`option`, `value`) VALUES (?, ?)")
                .execute(Tuple.of("main_server", "-1"))
                .await()
        }
}