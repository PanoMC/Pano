package com.panomc.platform.db.migration

import com.panomc.platform.annotation.Migration
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.DatabaseMigration
import io.vertx.kotlin.coroutines.await
import io.vertx.sqlclient.SqlConnection

@Suppress("ClassName")
@Migration
class DatabaseMigration_19_20(databaseManager: DatabaseManager) : DatabaseMigration(databaseManager) {
    override val FROM_SCHEME_VERSION = 19
    override val SCHEME_VERSION = 20
    override val SCHEME_VERSION_INFO =
        "Convert dates from string to BigInt."

    override val handlers: List<suspend (sqlConnection: SqlConnection) -> Unit> =
        listOf(
            updatePanelNotificationTableDateColumn(),
            updatePostTableDateColumn(),
            updatePostTableMoveDateColumn(),
            updatePostTableViewsColumn(),
            updateTicketTableDateColumn(),
            updateTicketTableLastUpdateColumn(),
            updateTicketMessageTableDateColumn(),
            updateTokenTableCreatedTimeColumn(),
            updateUserTableRegisterDateColumn()
        )

    private fun updatePanelNotificationTableDateColumn(): suspend (sqlConnection: SqlConnection) -> Unit =
        { sqlConnection: SqlConnection ->
            sqlConnection
                .query("ALTER TABLE `${getTablePrefix()}panel_notification` MODIFY `date` BIGINT(20);")
                .execute()
                .await()
        }

    private fun updatePostTableDateColumn(): suspend (sqlConnection: SqlConnection) -> Unit =
        { sqlConnection: SqlConnection ->
            sqlConnection
                .query("ALTER TABLE `${getTablePrefix()}post` MODIFY `date` BIGINT(20);")
                .execute()
                .await()
        }

    private fun updatePostTableMoveDateColumn(): suspend (sqlConnection: SqlConnection) -> Unit =
        { sqlConnection: SqlConnection ->
            sqlConnection
                .query("ALTER TABLE `${getTablePrefix()}post` MODIFY `move_date` BIGINT(20);")
                .execute()
                .await()
        }

    private fun updatePostTableViewsColumn(): suspend (sqlConnection: SqlConnection) -> Unit =
        { sqlConnection: SqlConnection ->
            sqlConnection
                .query("ALTER TABLE `${getTablePrefix()}post` MODIFY `views` BIGINT(20);")
                .execute()
                .await()
        }

    private fun updateTicketTableDateColumn(): suspend (sqlConnection: SqlConnection) -> Unit =
        { sqlConnection: SqlConnection ->
            sqlConnection
                .query("ALTER TABLE `${getTablePrefix()}ticket` MODIFY `date` BIGINT(20);")
                .execute()
                .await()
        }

    private fun updateTicketTableLastUpdateColumn(): suspend (sqlConnection: SqlConnection) -> Unit =
        { sqlConnection: SqlConnection ->
            sqlConnection
                .query("ALTER TABLE `${getTablePrefix()}ticket` MODIFY `last_update` BIGINT(20);")
                .execute()
                .await()
        }

    private fun updateTicketMessageTableDateColumn(): suspend (sqlConnection: SqlConnection) -> Unit =
        { sqlConnection: SqlConnection ->
            sqlConnection
                .query("ALTER TABLE `${getTablePrefix()}ticket_message` MODIFY `date` BIGINT(20);")
                .execute()
                .await()
        }

    private fun updateTokenTableCreatedTimeColumn(): suspend (sqlConnection: SqlConnection) -> Unit =
        { sqlConnection: SqlConnection ->
            sqlConnection
                .query("ALTER TABLE `${getTablePrefix()}token` MODIFY `created_time` BIGINT(20);")
                .execute()
                .await()
        }

    private fun updateUserTableRegisterDateColumn(): suspend (sqlConnection: SqlConnection) -> Unit =
        { sqlConnection: SqlConnection ->
            sqlConnection
                .query("ALTER TABLE `${getTablePrefix()}user` MODIFY `register_date` BIGINT(20);")
                .execute()
                .await()
        }

}