package com.panomc.platform.db.migration

import com.panomc.platform.annotation.Migration
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.DatabaseMigration
import io.vertx.kotlin.coroutines.await
import io.vertx.sqlclient.SqlClient

@Migration
class DatabaseMigration29To30(databaseManager: DatabaseManager) : DatabaseMigration(databaseManager) {
    override val FROM_SCHEME_VERSION = 29
    override val SCHEME_VERSION = 30
    override val SCHEME_VERSION_INFO =
        "Convert date from string to BigInt in website view table."

    override val handlers: List<suspend (sqlClient: SqlClient) -> Unit> =
        listOf(
            updatePanelNotificationTableDateColumn(),
        )

    private fun updatePanelNotificationTableDateColumn(): suspend (sqlClient: SqlClient) -> Unit =
        { sqlClient: SqlClient ->
            sqlClient
                .query("ALTER TABLE `${getTablePrefix()}website_view` MODIFY `date` BIGINT(20);")
                .execute()
                .await()
        }
}