package com.panomc.platform.db.implementation

import com.panomc.platform.annotation.Dao
import com.panomc.platform.db.DaoImpl
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.dao.PermissionGroupDao
import com.panomc.platform.db.model.PermissionGroup
import io.vertx.kotlin.coroutines.await
import io.vertx.mysqlclient.MySQLClient
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
                              `id` bigint NOT NULL AUTO_INCREMENT,
                              `name` varchar(32) NOT NULL UNIQUE,
                              PRIMARY KEY (`id`)
                            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Permission Group Table';
                        """
            )
            .execute()
            .await()

        createAdminPermission(sqlConnection)
    }

    override suspend fun isThereByName(
        name: String,
        sqlConnection: SqlConnection
    ): Boolean {
        val query =
            "SELECT COUNT(`name`) FROM `${getTablePrefix() + tableName}` where `name` = ?"

        val rows: RowSet<Row> = sqlConnection
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    name
                )
            ).await()

        return rows.toList()[0].getLong(0) != 0L
    }

    override suspend fun isThere(permissionGroup: PermissionGroup, sqlConnection: SqlConnection): Boolean {
        val query =
            "SELECT COUNT(`id`) FROM `${getTablePrefix() + tableName}` where `id` = ? and `name` = ?"

        val rows: RowSet<Row> = sqlConnection
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    permissionGroup.id,
                    permissionGroup.name
                )
            ).await()

        return rows.toList()[0].getLong(0) != 0L
    }

    override suspend fun isThereById(
        id: Long,
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

        return rows.toList()[0].getLong(0) != 0L
    }

    override suspend fun add(
        permissionGroup: PermissionGroup,
        sqlConnection: SqlConnection
    ): Long {
        val query = "INSERT INTO `${getTablePrefix() + tableName}` (name) VALUES (?)"

        val rows: RowSet<Row> = sqlConnection
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    permissionGroup.name
                )
            ).await()

        return rows.property(MySQLClient.LAST_INSERTED_ID)
    }

    override suspend fun getPermissionGroupById(
        id: Long,
        sqlConnection: SqlConnection
    ): PermissionGroup? {
        val query =
            "SELECT `id`, `name` FROM `${getTablePrefix() + tableName}` where `id` = ?"

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

        val row = rows.toList()[0]

        return PermissionGroup.from(row)
    }

    override suspend fun getPermissionGroupIdByName(
        name: String,
        sqlConnection: SqlConnection
    ): Long? {
        val query =
            "SELECT id FROM `${getTablePrefix() + tableName}` where `name` = ?"

        val rows: RowSet<Row> = sqlConnection
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    name
                )
            ).await()

        if (rows.size() == 0) {
            return null
        }

        return rows.toList()[0].getLong(0)
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

        return PermissionGroup.from(rows)
    }

    override suspend fun getPermissionGroupsByPage(page: Long, sqlConnection: SqlConnection): List<PermissionGroup> {
        val query =
            "SELECT `id`, `name` FROM `${getTablePrefix() + tableName}` ORDER BY `ID` ASC LIMIT 10 ${if (page == 1L) "" else "OFFSET ${(page - 1) * 10}"}"

        val rows: RowSet<Row> = sqlConnection
            .preparedQuery(query)
            .execute()
            .await()

        return PermissionGroup.from(rows)
    }

    override suspend fun countPermissionGroups(sqlConnection: SqlConnection): Long {
        val query =
            "SELECT COUNT(`id`) FROM `${getTablePrefix() + tableName}`"

        val rows: RowSet<Row> = sqlConnection
            .preparedQuery(query)
            .execute()
            .await()

        return rows.toList()[0].getLong(0)
    }

    override suspend fun deleteById(
        id: Long,
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
        val isThere = isThereByName(adminPermissionName, sqlConnection)

        if (isThere) {
            return
        }

        add(PermissionGroup(name = adminPermissionName), sqlConnection)
    }
}