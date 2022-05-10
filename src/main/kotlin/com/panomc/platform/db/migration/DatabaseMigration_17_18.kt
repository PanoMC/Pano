package com.panomc.platform.db.migration

import com.panomc.platform.annotation.Migration
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.DatabaseMigration
import com.panomc.platform.db.model.PermissionGroup
import io.vertx.core.AsyncResult
import io.vertx.sqlclient.SqlConnection
import io.vertx.sqlclient.Tuple

@Suppress("ClassName")
@Migration
class DatabaseMigration_17_18(databaseManager: DatabaseManager) : DatabaseMigration(databaseManager) {
    override val FROM_SCHEME_VERSION = 17
    override val SCHEME_VERSION = 18
    override val SCHEME_VERSION_INFO =
        "Delete permissions, create permission group and permission group permission tables."

    override val handlers: List<(sqlConnection: SqlConnection, handler: (asyncResult: AsyncResult<*>) -> Unit) -> Unit> =
        listOf(
            deletePermissions(),
            createPermissionGroupTable(),
            createPermissionGroupPermsTable(),
            createAdminPermissionGroup(),
            changePermissionIDFieldName()
        )

    private fun deletePermissions(): (sqlConnection: SqlConnection, handler: (asyncResult: AsyncResult<*>) -> Unit) -> Unit =
        { sqlConnection, handler ->
            sqlConnection
                .query("DELETE FROM `${getTablePrefix()}permission`")
                .execute {
                    handler.invoke(it)
                }
        }

    private fun createPermissionGroupTable(): (sqlConnection: SqlConnection, handler: (asyncResult: AsyncResult<*>) -> Unit) -> Unit =
        { sqlConnection, handler ->
            sqlConnection
                .query(
                    """
                            CREATE TABLE IF NOT EXISTS `${getTablePrefix()}permission_group` (
                              `id` int NOT NULL AUTO_INCREMENT,
                              `name` varchar(32) NOT NULL UNIQUE,
                              PRIMARY KEY (`id`)
                            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Permission Group Table';
                        """
                )
                .execute {
                    handler.invoke(it)
                }
        }

    private fun createPermissionGroupPermsTable(): (sqlConnection: SqlConnection, handler: (asyncResult: AsyncResult<*>) -> Unit) -> Unit =
        { sqlConnection, handler ->
            sqlConnection
                .query(
                    """
                            CREATE TABLE IF NOT EXISTS `${getTablePrefix()}permission_group_perms` (
                              `id` int NOT NULL AUTO_INCREMENT,
                              `permission_id` int NOT NULL,
                              `permission_group_id` int NOT NULL,
                              PRIMARY KEY (`id`)
                            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Permission Group Permission Table';
                        """
                )
                .execute {
                    handler.invoke(it)
                }
        }

    private fun createAdminPermissionGroup(): (sqlConnection: SqlConnection, handler: (asyncResult: AsyncResult<*>) -> Unit) -> Unit =
        { sqlConnection, handler ->
            val permissionGroup = PermissionGroup(-1, "admin")

            val query = "INSERT INTO `${getTablePrefix()}permission_group` (name) VALUES (?)"

            sqlConnection
                .preparedQuery(query)
                .execute(
                    Tuple.of(
                        permissionGroup.name
                    )
                ) { queryResult ->
                    handler.invoke(queryResult)
                }
        }

    private fun changePermissionIDFieldName(): (sqlConnection: SqlConnection, handler: (asyncResult: AsyncResult<*>) -> Unit) -> Unit =
        { sqlConnection, handler ->
            sqlConnection
                .query("ALTER TABLE `${getTablePrefix()}user` RENAME COLUMN `permission_id` TO `permission_group_id`;")
                .execute {
                    handler.invoke(it)
                }
        }
}