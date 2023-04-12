package com.panomc.platform.db.migration

import com.panomc.platform.annotation.Migration
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.DatabaseMigration
import com.panomc.platform.db.model.Permission
import io.vertx.kotlin.coroutines.await
import io.vertx.sqlclient.SqlClient
import io.vertx.sqlclient.Tuple

@Migration
class DatabaseMigration18To19(databaseManager: DatabaseManager) : DatabaseMigration(databaseManager) {
    override val FROM_SCHEME_VERSION = 18
    override val SCHEME_VERSION = 19
    override val SCHEME_VERSION_INFO =
        "Improve permission name type, add icon_name field and create system permissions."

    override val handlers: List<suspend (sqlClient: SqlClient) -> Unit> =
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

    private fun updatePermissionTableNameColumn(): suspend (sqlClient: SqlClient) -> Unit =
        { sqlClient: SqlClient ->
            sqlClient
                .query("ALTER TABLE `${getTablePrefix()}permission` MODIFY `name` varchar(128);")
                .execute()
                .await()
        }

    private fun addIconNameFieldToPermissionTable(): suspend (sqlClient: SqlClient) -> Unit =
        { sqlClient: SqlClient ->
            sqlClient
                .query("ALTER TABLE `${getTablePrefix()}permission` ADD `icon_name` varchar(128) NOT NULL DEFAULT '';")
                .execute()
                .await()
        }

    private fun createManageServersPermission(): suspend (sqlClient: SqlClient) -> Unit =
        { sqlClient: SqlClient ->
            val permission = Permission(name = "manage_servers", iconName = "fa-cubes")

            val query = "INSERT INTO `${getTablePrefix()}permission` (`name`, `icon_name`) VALUES (?, ?)"

            sqlClient
                .preparedQuery(query)
                .execute(
                    Tuple.of(
                        permission.name,
                        permission.iconName
                    )
                )
                .await()
        }

    private fun createManagePostsPermission(): suspend (sqlClient: SqlClient) -> Unit =
        { sqlClient: SqlClient ->
            val permission = Permission(name = "manage_posts", iconName = "fa-sticky-note")

            val query = "INSERT INTO `${getTablePrefix()}permission` (`name`, `icon_name`) VALUES (?, ?)"

            sqlClient
                .preparedQuery(query)
                .execute(
                    Tuple.of(
                        permission.name,
                        permission.iconName
                    )
                )
                .await()
        }

    private fun createManageTicketsPermission(): suspend (sqlClient: SqlClient) -> Unit =
        { sqlClient: SqlClient ->
            val permission = Permission(name = "manage_tickets", iconName = "fa-ticket-alt")

            val query = "INSERT INTO `${getTablePrefix()}permission` (`name`, `icon_name`) VALUES (?, ?)"

            sqlClient
                .preparedQuery(query)
                .execute(
                    Tuple.of(
                        permission.name,
                        permission.iconName
                    )
                )
                .await()
        }

    private fun createManagePlayersPermission(): suspend (sqlClient: SqlClient) -> Unit =
        { sqlClient: SqlClient ->
            val permission = Permission(name = "manage_players", iconName = "fa-users")

            val query = "INSERT INTO `${getTablePrefix()}permission` (`name`, `icon_name`) VALUES (?, ?)"

            sqlClient
                .preparedQuery(query)
                .execute(
                    Tuple.of(
                        permission.name,
                        permission.iconName
                    )
                )
                .await()
        }

    private fun createManageViewPermission(): suspend (sqlClient: SqlClient) -> Unit =
        { sqlClient: SqlClient ->
            val permission = Permission(name = "manage_view", iconName = "fa-palette")

            val query = "INSERT INTO `${getTablePrefix()}permission` (`name`, `icon_name`) VALUES (?, ?)"

            sqlClient
                .preparedQuery(query)
                .execute(
                    Tuple.of(
                        permission.name,
                        permission.iconName
                    )
                )
                .await()
        }

    private fun createManageAddonsPermission(): suspend (sqlClient: SqlClient) -> Unit =
        { sqlClient: SqlClient ->
            val permission = Permission(name = "manage_addons", iconName = "fa-puzzle-piece")

            val query = "INSERT INTO `${getTablePrefix()}permission` (`name`, `icon_name`) VALUES (?, ?)"

            sqlClient
                .preparedQuery(query)
                .execute(
                    Tuple.of(
                        permission.name,
                        permission.iconName
                    )
                )
                .await()
        }

    private fun createManagePlatformSettingsPermission(): suspend (sqlClient: SqlClient) -> Unit =
        { sqlClient: SqlClient ->
            val permission = Permission(name = "manage_platform_settings", iconName = "fa-cog")

            val query = "INSERT INTO `${getTablePrefix()}permission` (`name`, `icon_name`) VALUES (?, ?)"

            sqlClient
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