package com.panomc.platform.db.migration

import com.panomc.platform.annotation.Migration
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.DatabaseMigration
import com.panomc.platform.db.model.Permission
import io.vertx.kotlin.coroutines.await
import io.vertx.sqlclient.SqlConnection
import io.vertx.sqlclient.Tuple

@Suppress("ClassName")
@Migration
class DatabaseMigration_18_19(databaseManager: DatabaseManager) : DatabaseMigration(databaseManager) {
    override val FROM_SCHEME_VERSION = 18
    override val SCHEME_VERSION = 19
    override val SCHEME_VERSION_INFO =
        "Improve permission name type, add icon_name field and create system permissions."

    override val handlers: List<suspend (sqlConnection: SqlConnection) -> Unit> =
        listOf(
            updatePermissionTableNameColumn(),
            addIconNameFieldToPermissionTable(),
            createManageServersPermission(),
            createManagePostsPermission(),
            createManageTicketsPermission(),
            createManagePlayersPermission(),
            createManageViewPermission(),
            createManageAddonsPermission(),
            createManagePlatformSettingsPermission()
        )

    private fun updatePermissionTableNameColumn(): suspend (sqlConnection: SqlConnection) -> Unit =
        { sqlConnection: SqlConnection ->
            sqlConnection
                .query("ALTER TABLE `${getTablePrefix()}permission` MODIFY `name` varchar(128);")
                .execute()
                .await()
        }

    private fun addIconNameFieldToPermissionTable(): suspend (sqlConnection: SqlConnection) -> Unit =
        { sqlConnection: SqlConnection ->
            sqlConnection
                .query("ALTER TABLE `${getTablePrefix()}permission` ADD `icon_name` varchar(128) NOT NULL DEFAULT '';")
                .execute()
                .await()
        }

    private fun createManageServersPermission(): suspend (sqlConnection: SqlConnection) -> Unit =
        { sqlConnection: SqlConnection ->
            val permission = Permission(-1, "manage_servers", "fa-cubes")

            val query = "INSERT INTO `${getTablePrefix()}permission` (`name`, `icon_name`) VALUES (?, ?)"

            sqlConnection
                .preparedQuery(query)
                .execute(
                    Tuple.of(
                        permission.name,
                        permission.iconName
                    )
                )
                .await()
        }

    private fun createManagePostsPermission(): suspend (sqlConnection: SqlConnection) -> Unit =
        { sqlConnection: SqlConnection ->
            val permission = Permission(-1, "manage_posts", "fa-sticky-note")

            val query = "INSERT INTO `${getTablePrefix()}permission` (`name`, `icon_name`) VALUES (?, ?)"

            sqlConnection
                .preparedQuery(query)
                .execute(
                    Tuple.of(
                        permission.name,
                        permission.iconName
                    )
                )
                .await()
        }

    private fun createManageTicketsPermission(): suspend (sqlConnection: SqlConnection) -> Unit =
        { sqlConnection: SqlConnection ->
            val permission = Permission(-1, "manage_tickets", "fa-ticket-alt")

            val query = "INSERT INTO `${getTablePrefix()}permission` (`name`, `icon_name`) VALUES (?, ?)"

            sqlConnection
                .preparedQuery(query)
                .execute(
                    Tuple.of(
                        permission.name,
                        permission.iconName
                    )
                )
                .await()
        }

    private fun createManagePlayersPermission(): suspend (sqlConnection: SqlConnection) -> Unit =
        { sqlConnection: SqlConnection ->
            val permission = Permission(-1, "manage_players", "fa-users")

            val query = "INSERT INTO `${getTablePrefix()}permission` (`name`, `icon_name`) VALUES (?, ?)"

            sqlConnection
                .preparedQuery(query)
                .execute(
                    Tuple.of(
                        permission.name,
                        permission.iconName
                    )
                )
                .await()
        }

    private fun createManageViewPermission(): suspend (sqlConnection: SqlConnection) -> Unit =
        { sqlConnection: SqlConnection ->
            val permission = Permission(-1, "manage_view", "fa-palette")

            val query = "INSERT INTO `${getTablePrefix()}permission` (`name`, `icon_name`) VALUES (?, ?)"

            sqlConnection
                .preparedQuery(query)
                .execute(
                    Tuple.of(
                        permission.name,
                        permission.iconName
                    )
                )
                .await()
        }

    private fun createManageAddonsPermission(): suspend (sqlConnection: SqlConnection) -> Unit =
        { sqlConnection: SqlConnection ->
            val permission = Permission(-1, "manage_addons", "fa-puzzle-piece")

            val query = "INSERT INTO `${getTablePrefix()}permission` (`name`, `icon_name`) VALUES (?, ?)"

            sqlConnection
                .preparedQuery(query)
                .execute(
                    Tuple.of(
                        permission.name,
                        permission.iconName
                    )
                )
                .await()
        }

    private fun createManagePlatformSettingsPermission(): suspend (sqlConnection: SqlConnection) -> Unit =
        { sqlConnection: SqlConnection ->
            val permission = Permission(-1, "manage_platform_settings", "fa-cog")

            val query = "INSERT INTO `${getTablePrefix()}permission` (`name`, `icon_name`) VALUES (?, ?)"

            sqlConnection
                .preparedQuery(query)
                .execute(
                    Tuple.of(
                        permission.name,
                        permission.iconName
                    )
                )
                .await()
        }
}