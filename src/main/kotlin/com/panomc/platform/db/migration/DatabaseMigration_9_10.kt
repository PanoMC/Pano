package com.panomc.platform.db.migration

import com.panomc.platform.annotation.Migration
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.DatabaseMigration
import io.vertx.kotlin.coroutines.await
import io.vertx.sqlclient.SqlConnection

@Suppress("ClassName")
@Migration
class DatabaseMigration_9_10(databaseManager: DatabaseManager) : DatabaseMigration(databaseManager) {
    override val FROM_SCHEME_VERSION = 9
    override val SCHEME_VERSION = 10
    override val SCHEME_VERSION_INFO = "Update post, ticket and few other tables to be better."

    override val handlers: List<suspend (sqlConnection: SqlConnection) -> Unit> =
        listOf(
            updatePostTableDateColumn(),
            updateTicketTableDateColumn(),
            updatePostTableTitleColumn(),
            updatePostTableMoveDateColumn(),
            updatePostTableCategoryIdColumn(),
            updateTicketTableTicketCategoryIdColumn(),
            updateTicketTableTitleColumn(),
            updateTicketCategoryTableTitleColumn(),
            updatePostCategoryTableTitleColumn(),
            updateTokenTableCreatedTimeColumn()
        )

    private fun updatePostTableDateColumn(): suspend (sqlConnection: SqlConnection) -> Unit =
        { sqlConnection: SqlConnection ->
            sqlConnection
                .query("ALTER TABLE `${getTablePrefix()}post` MODIFY `date` MEDIUMTEXT;")
                .execute()
                .await()
        }

    private fun updatePostTableMoveDateColumn(): suspend (sqlConnection: SqlConnection) -> Unit =
        { sqlConnection: SqlConnection ->
            sqlConnection
                .query(" ALTER TABLE `${getTablePrefix()}post` MODIFY `move_date` MEDIUMTEXT;")
                .execute()
                .await()
        }

    private fun updatePostTableTitleColumn(): suspend (sqlConnection: SqlConnection) -> Unit =
        { sqlConnection: SqlConnection ->
            sqlConnection
                .query("ALTER TABLE `${getTablePrefix()}post` MODIFY `title` MEDIUMTEXT;")
                .execute()
                .await()
        }

    private fun updatePostTableCategoryIdColumn(): suspend (sqlConnection: SqlConnection) -> Unit =
        { sqlConnection: SqlConnection ->
            sqlConnection
                .query("ALTER TABLE `${getTablePrefix()}post` MODIFY `category_id` int(11);")
                .execute()
                .await()
        }

    private fun updatePostCategoryTableTitleColumn(): suspend (sqlConnection: SqlConnection) -> Unit =
        { sqlConnection: SqlConnection ->
            sqlConnection
                .query("ALTER TABLE `${getTablePrefix()}post_category` MODIFY `title` MEDIUMTEXT;")
                .execute()
                .await()
        }

    private fun updateTicketTableDateColumn(): suspend (sqlConnection: SqlConnection) -> Unit =
        { sqlConnection: SqlConnection ->
            sqlConnection
                .query("ALTER TABLE `${getTablePrefix()}ticket` MODIFY `date` MEDIUMTEXT;")
                .execute()
                .await()
        }

    private fun updateTicketTableTicketCategoryIdColumn(): suspend (sqlConnection: SqlConnection) -> Unit =
        { sqlConnection: SqlConnection ->
            sqlConnection
                .query("ALTER TABLE `${getTablePrefix()}ticket` CHANGE `ticket_category_id` `category_id` int(11);")
                .execute()
                .await()
        }

    private fun updateTicketTableTitleColumn(): suspend (sqlConnection: SqlConnection) -> Unit =
        { sqlConnection: SqlConnection ->
            sqlConnection
                .query("ALTER TABLE `${getTablePrefix()}ticket` MODIFY `title` MEDIUMTEXT;")
                .execute()
                .await()
        }

    private fun updateTicketCategoryTableTitleColumn(): suspend (sqlConnection: SqlConnection) -> Unit =
        { sqlConnection: SqlConnection ->
            sqlConnection
                .query("ALTER TABLE `${getTablePrefix()}ticket_category` MODIFY `title` MEDIUMTEXT;")
                .execute()
                .await()
        }

    private fun updateTokenTableCreatedTimeColumn(): suspend (sqlConnection: SqlConnection) -> Unit =
        { sqlConnection: SqlConnection ->
            sqlConnection
                .query("ALTER TABLE `${getTablePrefix()}token` MODIFY `created_time` MEDIUMTEXT;")
                .execute()
                .await()
        }
}