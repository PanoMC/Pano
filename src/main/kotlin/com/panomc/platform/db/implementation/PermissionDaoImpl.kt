package com.panomc.platform.db.implementation

import com.panomc.platform.annotation.Dao
import com.panomc.platform.db.DaoImpl
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.dao.PermissionDao
import com.panomc.platform.db.model.Permission
import io.vertx.kotlin.coroutines.await
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.RowSet
import io.vertx.sqlclient.SqlConnection
import io.vertx.sqlclient.Tuple

@Dao
class PermissionDaoImpl(databaseManager: DatabaseManager) : DaoImpl(databaseManager, "permission"), PermissionDao {
    override suspend fun init(sqlConnection: SqlConnection) {
        sqlConnection
            .query(
                """
                            CREATE TABLE IF NOT EXISTS `${getTablePrefix() + tableName}` (
                              `id` int NOT NULL AUTO_INCREMENT,
                              `name` varchar(128) NOT NULL UNIQUE,
                              `icon_name` varchar(128) NOT NULL DEFAULT '',
                              PRIMARY KEY (`id`)
                            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Permission Table';
                        """
            )
            .execute()
            .await()

        val permissions = listOf(
            Permission(-1, "access_panel", "fa-cubes"),
            Permission(-1, "manage_servers", "fa-cubes"),
            Permission(-1, "manage_posts", "fa-sticky-note"),
            Permission(-1, "manage_tickets", "fa-ticket-alt"),
            Permission(-1, "manage_players", "fa-users"),
            Permission(-1, "manage_view", "fa-palette"),
            Permission(-1, "manage_addons", "fa-puzzle-piece"),
            Permission(-1, "manage_platform_settings", "fa-cog")
        )

        permissions.forEach { add(it, sqlConnection) }
    }

    override suspend fun isTherePermission(
        permission: Permission,
        sqlConnection: SqlConnection
    ): Boolean {
        val query =
            "SELECT COUNT(`name`) FROM `${getTablePrefix() + tableName}` where `name` = ?"

        val rows: RowSet<Row> = sqlConnection
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    permission.name
                )
            ).await()

        return rows.toList()[0].getInteger(0) != 0
    }

    override suspend fun isTherePermissionByID(
        id: Int,
        sqlConnection: SqlConnection
    ): Boolean {
        val query =
            "SELECT COUNT(`id`) FROM `${getTablePrefix() + tableName}` where `id` = ?"

        val rows: RowSet<Row> = sqlConnection
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    id
                )
            ).await()

        return rows.toList()[0].getInteger(0) != 0
    }

    override suspend fun add(
        permission: Permission,
        sqlConnection: SqlConnection
    ) {
        val query = "INSERT INTO `${getTablePrefix() + tableName}` (`name`, `icon_name`) VALUES (?, ?)"

        sqlConnection
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    permission.name,
                    permission.iconName
                )
            ).await()
    }

    override suspend fun getPermissionID(
        permission: Permission,
        sqlConnection: SqlConnection
    ): Int {
        val query =
            "SELECT id FROM `${getTablePrefix() + tableName}` where `name` = ?"

        val rows: RowSet<Row> = sqlConnection
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    permission.name
                )
            ).await()

        return rows.toList()[0].getInteger(0)
    }

    override suspend fun getPermissionByID(
        id: Int,
        sqlConnection: SqlConnection
    ): Permission? {
        val query =
            "SELECT `name`, `icon_name` FROM `${getTablePrefix() + tableName}` where `id` = ?"

        val rows: RowSet<Row> = sqlConnection
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    id
                )
            ).await()

        if (rows.size() == 0) {
            return null
        }

        val permissionRow = rows.toList()[0]
        val permission = Permission(id, permissionRow.getString(0), permissionRow.getString(1))

        return permission
    }

    override suspend fun getPermissions(
        sqlConnection: SqlConnection
    ): List<Permission> {
        val query =
            "SELECT `id`, `name`, `icon_name` FROM `${getTablePrefix() + tableName}`"

        val rows: RowSet<Row> = sqlConnection
            .preparedQuery(query)
            .execute()
            .await()

        val permissions = rows.map { row ->
            Permission(row.getInteger(0), row.getString(1), row.getString(2))
        }

        return permissions
    }
}