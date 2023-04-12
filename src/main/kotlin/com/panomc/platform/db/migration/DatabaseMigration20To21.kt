package com.panomc.platform.db.migration

import com.panomc.platform.annotation.Migration
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.DatabaseMigration
import io.vertx.kotlin.coroutines.await
import io.vertx.sqlclient.SqlClient

@Migration
class DatabaseMigration20To21(databaseManager: DatabaseManager) : DatabaseMigration(databaseManager) {
    override val FROM_SCHEME_VERSION = 20
    override val SCHEME_VERSION = 21
    override val SCHEME_VERSION_INFO =
        "Drop token table."

    override val handlers: List<suspend (sqlClient: SqlClient) -> Unit> =
        listOf(
            dropTokenTable(),
        )

    private fun dropTokenTable(): suspend (sqlClient: SqlClient) -> Unit =
        { sqlClient: SqlClient ->
            sqlClient
                .query("DROP TABLE IF EXISTS `${getTablePrefix()}token`;")
                .execute()
                .await()
        }

}