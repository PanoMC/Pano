package com.panomc.platform.db.migration

import com.panomc.platform.annotation.Migration
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.DatabaseMigration
import com.panomc.platform.db.model.PermissionGroup
import io.vertx.kotlin.coroutines.await
import io.vertx.sqlclient.SqlConnection
import io.vertx.sqlclient.Tuple

@Migration
class DatabaseMigration17To18(databaseManager: DatabaseManager) : DatabaseMigration(databaseManager) {
    override val FROM_SCHEME_VERSION = 17
    override val SCHEME_VERSION = 18
    override val SCHEME_VERSION_INFO =
        "Delete permissions, create permission group and permission group permission tables."

    override val handlers: List<suspend (sqlConnection: SqlConnection) -> Unit> =
        listOf(
            deletePermissions(),
            createPermissionGroupTable(),
            createPermissionGroupPermsTable(),
            createAdminPermissionGroup(),
            changePermissionIdFieldName()
        )

    private fun deletePermissions(): suspend (sqlConnection: SqlConnection) -> Unit =
        { sqlConnection: SqlConnection ->
            sqlConnection
                .query("DELETE FROM `${getTablePrefix()}permission`")
                .execute()
                .await()
        }

    private fun createPermissionGroupTable(): suspend (sqlConnection: SqlConnection) -> Unit =
        { sqlConnection: SqlConnection ->
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
                .execute()
                .await()
        }

    private fun createPermissionGroupPermsTable(): suspend (sqlConnection: SqlConnection) -> Unit =
        { sqlConnection: SqlConnection ->
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
                .execute()
                .await()
        }

    private fun createAdminPermissionGroup(): suspend (sqlConnection: SqlConnection) -> Unit =
        { sqlConnection: SqlConnection ->
            val permissionGroup = PermissionGroup(name = "admin")

            val query = "INSERT INTO `${getTablePrefix()}permission_group` (name) VALUES (?)"

            sqlConnection
                .preparedQuery(query)
                .execute(
                    Tuple.of(
                        permissionGroup.name
                    )
                )
                .await()
        }

    private fun changePermissionIdFieldName(): suspend (sqlConnection: SqlConnection) -> Unit =
        { sqlConnection: SqlConnection ->
            sqlConnection
                .query("ALTER TABLE `${getTablePrefix()}user` RENAME COLUMN `permission_id` TO `permission_group_id`;")
                .execute()
                .await()
        }
}