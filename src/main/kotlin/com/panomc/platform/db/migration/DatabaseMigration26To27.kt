package com.panomc.platform.db.migration

import com.panomc.platform.annotation.Migration
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.DatabaseMigration
import io.vertx.kotlin.coroutines.await
import io.vertx.sqlclient.SqlClient

@Migration
class DatabaseMigration26To27(databaseManager: DatabaseManager) : DatabaseMigration(databaseManager) {
    override val FROM_SCHEME_VERSION = 26
    override val SCHEME_VERSION = 27
    override val SCHEME_VERSION_INFO =
        "Convert ids in tables to bigint type."

    override val handlers: List<suspend (sqlClient: SqlClient) -> Unit> =
        listOf(
            updatePanelConfigTable(),
            updatePanelNotificationTable(),
            updatePermissionTable(),
            updatePermissionGroupTable(),
            updatePermissionGroupPermsTable(),
            updatePostCategoryTable(),
            updatePostTable(),
            updateServerTable(),
            updateSystemPropertyTable(),
            updateTicketCategoryTable(),
            updateTicketTable(),
            updateTicketMessageTable(),
            updateUserTable()
        )

    private fun updatePanelConfigTable(): suspend (sqlClient: SqlClient) -> Unit =
        { sqlClient: SqlClient ->
            sqlClient
                .query("ALTER TABLE  `${getTablePrefix()}panel_config` MODIFY `id` bigint NOT NULL AUTO_INCREMENT;")
                .execute()
                .await()

            sqlClient
                .query("ALTER TABLE  `${getTablePrefix()}panel_config` MODIFY `user_id` bigint NOT NULL;")
                .execute()
                .await()
        }

    private fun updatePanelNotificationTable(): suspend (sqlClient: SqlClient) -> Unit =
        { sqlClient: SqlClient ->
            sqlClient
                .query("ALTER TABLE  `${getTablePrefix()}panel_notification` MODIFY `id` bigint NOT NULL AUTO_INCREMENT;")
                .execute()
                .await()

            sqlClient
                .query("ALTER TABLE  `${getTablePrefix()}panel_notification` MODIFY `user_id` bigint NOT NULL;")
                .execute()
                .await()
        }

    private fun updatePermissionTable(): suspend (sqlClient: SqlClient) -> Unit =
        { sqlClient: SqlClient ->
            sqlClient
                .query("ALTER TABLE  `${getTablePrefix()}permission` MODIFY `id` bigint NOT NULL AUTO_INCREMENT;")
                .execute()
                .await()
        }

    private fun updatePermissionGroupTable(): suspend (sqlClient: SqlClient) -> Unit =
        { sqlClient: SqlClient ->
            sqlClient
                .query("ALTER TABLE  `${getTablePrefix()}permission_group` MODIFY `id` bigint NOT NULL AUTO_INCREMENT;")
                .execute()
                .await()
        }

    private fun updatePermissionGroupPermsTable(): suspend (sqlClient: SqlClient) -> Unit =
        { sqlClient: SqlClient ->
            sqlClient
                .query("ALTER TABLE  `${getTablePrefix()}permission_group_perms` MODIFY `id` bigint NOT NULL AUTO_INCREMENT;")
                .execute()
                .await()

            sqlClient
                .query("ALTER TABLE  `${getTablePrefix()}permission_group_perms` MODIFY `permission_id` bigint NOT NULL;")
                .execute()
                .await()

            sqlClient
                .query("ALTER TABLE  `${getTablePrefix()}permission_group_perms` MODIFY `permission_group_id` bigint NOT NULL;")
                .execute()
                .await()
        }

    private fun updatePostCategoryTable(): suspend (sqlClient: SqlClient) -> Unit =
        { sqlClient: SqlClient ->
            sqlClient
                .query("ALTER TABLE  `${getTablePrefix()}post_category` MODIFY `id` bigint NOT NULL AUTO_INCREMENT;")
                .execute()
                .await()
        }

    private fun updatePostTable(): suspend (sqlClient: SqlClient) -> Unit =
        { sqlClient: SqlClient ->
            sqlClient
                .query("ALTER TABLE  `${getTablePrefix()}post` MODIFY `id` bigint NOT NULL AUTO_INCREMENT;")
                .execute()
                .await()

            sqlClient
                .query("ALTER TABLE  `${getTablePrefix()}post` MODIFY `category_id` bigint NOT NULL;")
                .execute()
                .await()

            sqlClient
                .query("ALTER TABLE  `${getTablePrefix()}post` MODIFY `writer_user_id` bigint NOT NULL;")
                .execute()
                .await()
        }

    private fun updateServerTable(): suspend (sqlClient: SqlClient) -> Unit =
        { sqlClient: SqlClient ->
            sqlClient
                .query("ALTER TABLE  `${getTablePrefix()}server` MODIFY `id` bigint NOT NULL AUTO_INCREMENT;")
                .execute()
                .await()

            sqlClient
                .query("ALTER TABLE  `${getTablePrefix()}server` MODIFY `player_count` bigint NOT NULL;")
                .execute()
                .await()

            sqlClient
                .query("ALTER TABLE  `${getTablePrefix()}server` MODIFY `max_player_count` bigint NOT NULL;")
                .execute()
                .await()
        }

    private fun updateSystemPropertyTable(): suspend (sqlClient: SqlClient) -> Unit =
        { sqlClient: SqlClient ->
            sqlClient
                .query("ALTER TABLE  `${getTablePrefix()}system_property` MODIFY `id` bigint NOT NULL AUTO_INCREMENT;")
                .execute()
                .await()
        }

    private fun updateTicketCategoryTable(): suspend (sqlClient: SqlClient) -> Unit =
        { sqlClient: SqlClient ->
            sqlClient
                .query("ALTER TABLE  `${getTablePrefix()}ticket_category` MODIFY `id` bigint NOT NULL AUTO_INCREMENT;")
                .execute()
                .await()
        }

    private fun updateTicketTable(): suspend (sqlClient: SqlClient) -> Unit =
        { sqlClient: SqlClient ->
            sqlClient
                .query("ALTER TABLE  `${getTablePrefix()}ticket` MODIFY `id` bigint NOT NULL AUTO_INCREMENT;")
                .execute()
                .await()

            sqlClient
                .query("ALTER TABLE  `${getTablePrefix()}ticket` MODIFY `category_id` bigint NOT NULL;")
                .execute()
                .await()

            sqlClient
                .query("ALTER TABLE  `${getTablePrefix()}ticket` MODIFY `user_id` bigint NOT NULL;")
                .execute()
                .await()
        }

    private fun updateTicketMessageTable(): suspend (sqlClient: SqlClient) -> Unit =
        { sqlClient: SqlClient ->
            sqlClient
                .query("ALTER TABLE  `${getTablePrefix()}ticket_message` MODIFY `id` bigint NOT NULL AUTO_INCREMENT;")
                .execute()
                .await()

            sqlClient
                .query("ALTER TABLE  `${getTablePrefix()}ticket_message` MODIFY `user_id` bigint NOT NULL;")
                .execute()
                .await()

            sqlClient
                .query("ALTER TABLE  `${getTablePrefix()}ticket_message` MODIFY `ticket_id` bigint NOT NULL;")
                .execute()
                .await()
        }

    private fun updateUserTable(): suspend (sqlClient: SqlClient) -> Unit =
        { sqlClient: SqlClient ->
            sqlClient
                .query("ALTER TABLE  `${getTablePrefix()}user` MODIFY `id` bigint NOT NULL AUTO_INCREMENT;")
                .execute()
                .await()

            sqlClient
                .query("ALTER TABLE  `${getTablePrefix()}user` MODIFY `permission_group_id` bigint NOT NULL;")
                .execute()
                .await()
        }
}