package com.panomc.platform.db.migration

import com.panomc.platform.annotation.Migration
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.DatabaseMigration
import io.vertx.kotlin.coroutines.await
import io.vertx.sqlclient.SqlConnection
import io.vertx.sqlclient.Tuple

@Suppress("ClassName")
@Migration
class DatabaseMigration_2_3(databaseManager: DatabaseManager) : DatabaseMigration(databaseManager) {
    override val FROM_SCHEME_VERSION = 2
    override val SCHEME_VERSION = 3
    override val SCHEME_VERSION_INFO = "Removed connect_board feature."

    override val handlers: List<suspend (sqlConnection: SqlConnection) -> Unit> =
        listOf(
            deleteConnectBoardFeature()
        )

    private fun deleteConnectBoardFeature(): suspend (sqlConnection: SqlConnection) -> Unit =
        { sqlConnection: SqlConnection ->
            sqlConnection
                .preparedQuery("DELETE FROM ${getTablePrefix()}system_property WHERE `option` = ?")
                .execute(Tuple.of("show_connect_server_info"))
                .await()
        }
}