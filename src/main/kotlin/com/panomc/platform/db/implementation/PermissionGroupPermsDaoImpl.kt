package com.panomc.platform.db.implementation

import com.panomc.platform.annotation.Dao
import com.panomc.platform.db.DaoImpl
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.dao.PermissionGroupPermsDao
import com.panomc.platform.db.model.PermissionGroupPerms
import io.vertx.kotlin.coroutines.await
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.RowSet
import io.vertx.sqlclient.SqlConnection
import io.vertx.sqlclient.Tuple

@Dao
class PermissionGroupPermsDaoImpl(databaseManager: DatabaseManager) :
    DaoImpl(databaseManager, "permission_group_perms"), PermissionGroupPermsDao {
    override suspend fun init(sqlConnection: SqlConnection) {
        sqlConnection
            .query(
                """
                            CREATE TABLE IF NOT EXISTS `${getTablePrefix() + tableName}` (
                              `id` bigint NOT NULL AUTO_INCREMENT,
                              `permission_id` bigint NOT NULL,
                              `permission_group_id` bigint NOT NULL,
                              PRIMARY KEY (`id`)
                            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Permission Group Permission Table';
                        """
            )
            .execute()
            .await()
    }

    override suspend fun getPermissionGroupPerms(
        sqlConnection: SqlConnection
    ): List<PermissionGroupPerms> {
        val query =
            "SELECT `id`, `permission_id`, `permission_group_id` FROM `${getTablePrefix() + tableName}`"

        val rows: RowSet<Row> = sqlConnection
            .preparedQuery(query)
            .execute()
            .await()

        return PermissionGroupPerms.from(rows)
    }

    override suspend fun doesPermissionGroupHavePermission(
        permissionGroupId: Long,
        permissionId: Long,
        sqlConnection: SqlConnection
    ): Boolean {
        val query =
            "SELECT COUNT(`id`) FROM `${getTablePrefix() + tableName}` WHERE `permission_group_id` = ? AND  `permission_id` = ?"

        val rows: RowSet<Row> = sqlConnection
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    permissionGroupId,
                    permissionId
                )
            ).await()

        return rows.toList()[0].getLong(0) != 0L
    }

    override suspend fun addPermission(
        permissionGroupId: Long,
        permissionId: Long,
        sqlConnection: SqlConnection
    ) {
        val query =
            "INSERT INTO `${getTablePrefix() + tableName}` (`permission_id`, `permission_group_id`) VALUES (?, ?)"

        sqlConnection
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    permissionId,
                    permissionGroupId
                )
            ).await()
    }

    override suspend fun removePermission(
        permissionGroupId: Long,
        permissionId: Long,
        sqlConnection: SqlConnection
    ) {
        val query =
            "DELETE FROM `${getTablePrefix() + tableName}` WHERE `permission_group_id` = ? AND `permission_id` = ?"

        sqlConnection
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    permissionGroupId,
                    permissionId
                )
            ).await()
    }

    override suspend fun removePermissionGroup(
        permissionGroupId: Long,
        sqlConnection: SqlConnection,
    ) {
        val query =
            "DELETE FROM `${getTablePrefix() + tableName}` WHERE `permission_group_id` = ?"

        sqlConnection
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    permissionGroupId
                )
            ).await()
    }
}