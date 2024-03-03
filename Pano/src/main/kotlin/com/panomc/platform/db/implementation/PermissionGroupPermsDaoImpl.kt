package com.panomc.platform.db.implementation

import com.panomc.platform.annotation.Dao
import com.panomc.platform.db.dao.PermissionGroupPermsDao
import com.panomc.platform.db.model.PermissionGroupPerms
import io.vertx.kotlin.coroutines.await
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.RowSet
import io.vertx.sqlclient.SqlClient
import io.vertx.sqlclient.Tuple

@Dao
class PermissionGroupPermsDaoImpl : PermissionGroupPermsDao() {
    override suspend fun init(sqlClient: SqlClient) {
        sqlClient
            .query(
                """
                            CREATE TABLE IF NOT EXISTS `${getTablePrefix() + tableName}` (
                              `id` bigint NOT NULL AUTO_INCREMENT,
                              `permissionId` bigint NOT NULL,
                              `permissionGroupId` bigint NOT NULL,
                              PRIMARY KEY (`id`)
                            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Permission Group Permission Table';
                        """
            )
            .execute()
            .await()
    }

    override suspend fun getPermissionGroupPerms(
        sqlClient: SqlClient
    ): List<PermissionGroupPerms> {
        val query =
            "SELECT `id`, `permissionId`, `permissionGroupId` FROM `${getTablePrefix() + tableName}`"

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute()
            .await()

        return rows.toEntities()
    }

    override suspend fun getPermissionGroupPermsByPermissionId(
        permissionId: Long,
        sqlClient: SqlClient
    ): List<PermissionGroupPerms> {
        val query =
            "SELECT `id`, `permissionId`, `permissionGroupId` FROM `${getTablePrefix() + tableName}` WHERE `permissionId` = ?"

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute(Tuple.of(permissionId))
            .await()

        return rows.toEntities()
    }

    override suspend fun getByPermissionGroupId(
        permissionGroupId: Long,
        sqlClient: SqlClient
    ): List<PermissionGroupPerms> {
        val query =
            "SELECT `id`, `permissionId`, `permissionGroupId` FROM `${getTablePrefix() + tableName}` WHERE `permissionGroupId` = ?"

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute(Tuple.of(permissionGroupId))
            .await()

        return rows.toEntities()
    }

    override suspend fun countPermissionsByPermissionGroupId(
        permissionGroupId: Long,
        sqlClient: SqlClient
    ): Long {
        val query =
            "SELECT COUNT(`id`) FROM `${getTablePrefix() + tableName}` WHERE `permissionGroupId` = ?"

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute(Tuple.of(permissionGroupId))
            .await()

        return rows.toList()[0].getLong(0)
    }

    override suspend fun doesPermissionGroupHavePermission(
        permissionGroupId: Long,
        permissionId: Long,
        sqlClient: SqlClient
    ): Boolean {
        val query =
            "SELECT COUNT(`id`) FROM `${getTablePrefix() + tableName}` WHERE `permissionGroupId` = ? AND  `permissionId` = ?"

        val rows: RowSet<Row> = sqlClient
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
        sqlClient: SqlClient
    ) {
        val query =
            "INSERT INTO `${getTablePrefix() + tableName}` (`permissionId`, `permissionGroupId`) VALUES (?, ?)"

        sqlClient
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
        sqlClient: SqlClient
    ) {
        val query =
            "DELETE FROM `${getTablePrefix() + tableName}` WHERE `permissionGroupId` = ? AND `permissionId` = ?"

        sqlClient
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
        sqlClient: SqlClient,
    ) {
        val query =
            "DELETE FROM `${getTablePrefix() + tableName}` WHERE `permissionGroupId` = ?"

        sqlClient
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    permissionGroupId
                )
            ).await()
    }
}