package com.panomc.platform.db.implementation

import com.panomc.platform.annotation.Dao
import com.panomc.platform.db.DaoImpl
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.dao.PermissionGroupDao
import com.panomc.platform.db.model.PermissionGroup
import io.vertx.kotlin.coroutines.await
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.RowSet
import io.vertx.sqlclient.SqlConnection
import io.vertx.sqlclient.Tuple

@Dao
class PermissionGroupDaoImpl(databaseManager: DatabaseManager) : DaoImpl(databaseManager, "permission_group"),
    PermissionGroupDao {
    private val adminPermissionName = "admin"

    override suspend fun init(sqlConnection: SqlConnection) {
        sqlConnection
            .query(
                """
                            CREATE TABLE IF NOT EXISTS `${getTablePrefix() + tableName}` (
                              `id` int NOT NULL AUTO_INCREMENT,
                              `name` varchar(32) NOT NULL UNIQUE,
                              PRIMARY KEY (`id`)
                            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Permission Group Table';
                        """
            )
            .execute()
            .await()

        createAdminPermission(sqlConnection)
    }

    override suspend fun isThere(
        permissionGroup: PermissionGroup,
        sqlConnection: SqlConnection
    ): Boolean {
        val query =
            "SELECT COUNT(`name`) FROM `${getTablePrefix() + tableName}` where `name` = ?"

        val rows: RowSet<Row> = sqlConnection
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    permissionGroup.name
                )
            ).await()

        return rows.toList()[0].getInteger(0) != 0
    }

    override suspend fun isThereByID(
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
        permissionGroup: PermissionGroup,
        sqlConnection: SqlConnection
    ) {
        val query = "INSERT INTO `${getTablePrefix() + tableName}` (name) VALUES (?)"

        sqlConnection
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    permissionGroup.name
                )
            ).await()
    }

    override suspend fun getPermissionGroupByID(
        id: Int,
        sqlConnection: SqlConnection
    ): PermissionGroup? {
        val query =
            "SELECT `name` FROM `${getTablePrefix() + tableName}` where `id` = ?"

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

        return PermissionGroup(id, rows.toList()[0].getString(0))
    }

    override suspend fun getPermissionGroupID(
        permissionGroup: PermissionGroup,
        sqlConnection: SqlConnection
    ): Int? {
        val query =
            "SELECT id FROM `${getTablePrefix() + tableName}` where `name` = ?"

        val rows: RowSet<Row> = sqlConnection
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    permissionGroup.name
                )
            ).await()

        if (rows.size() == 0) {
            return null
        }

        return rows.toList()[0].getInteger(0)
    }

    override suspend fun getPermissionGroups(
        sqlConnection: SqlConnection
    ): List<PermissionGroup> {
        val query =
            "SELECT `id`, `name` FROM `${getTablePrefix() + tableName}` ORDER BY `ID` ASC"

        val rows: RowSet<Row> = sqlConnection
            .preparedQuery(query)
            .execute()
            .await()

        val permissionsGroups = rows.map { row ->
            PermissionGroup(row.getInteger(0), row.getString(1))
        }

        return permissionsGroups
    }

    override suspend fun deleteByID(
        id: Int,
        sqlConnection: SqlConnection
    ) {
        val query =
            "DELETE FROM `${getTablePrefix() + tableName}` WHERE `id` = ?"

        sqlConnection
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    id
                )
            )
            .await()
    }

    override suspend fun update(
        permissionGroup: PermissionGroup,
        sqlConnection: SqlConnection
    ) {
        val query =
            "UPDATE `${getTablePrefix() + tableName}` SET `name` = ? WHERE `id` = ?"

        sqlConnection
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    permissionGroup.name,
                    permissionGroup.id
                )
            )
            .await()
    }

    private suspend fun createAdminPermission(
        sqlConnection: SqlConnection
    ) {
        val isThere = isThere(PermissionGroup(-1, adminPermissionName), sqlConnection)

        if (isThere) {
            return
        }

        add(PermissionGroup(-1, adminPermissionName), sqlConnection)
    }
}