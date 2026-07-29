package com.panomc.platform.db.implementation

import com.panomc.platform.annotation.Dao
import com.panomc.platform.db.dao.PermissionTrackDao
import com.panomc.platform.db.model.PermissionTrack
import io.vertx.core.json.JsonArray
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.mysqlclient.MySQLClient
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.RowSet
import io.vertx.sqlclient.SqlClient
import io.vertx.sqlclient.Tuple

@Dao
class PermissionTrackDaoImpl : PermissionTrackDao() {
    override suspend fun init(sqlClient: SqlClient) {
        sqlClient
            .query(
                """
                            CREATE TABLE IF NOT EXISTS `${getTablePrefix() + tableName}` (
                              `id` bigint NOT NULL AUTO_INCREMENT,
                              `name` varchar(128) NOT NULL UNIQUE,
                              `description` text NOT NULL,
                              `groupIds` mediumtext NOT NULL,
                              `createdAt` BIGINT(20) NOT NULL,
                              `updatedAt` BIGINT(20) NOT NULL,
                              PRIMARY KEY (`id`)
                            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Permission Track table.';
                        """
            )
            .execute()
            .coAwait()
    }

    override suspend fun add(
        permissionTrack: PermissionTrack,
        sqlClient: SqlClient
    ): Long {
        val query =
            "INSERT INTO `${getTablePrefix() + tableName}` (`name`, `description`, `groupIds`, `createdAt`, `updatedAt`) VALUES (?, ?, ?, ?, ?)"

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    permissionTrack.name,
                    permissionTrack.description,
                    JsonArray(permissionTrack.groupIds).encode(),
                    permissionTrack.createdAt,
                    permissionTrack.updatedAt
                )
            ).coAwait()

        return rows.property(MySQLClient.LAST_INSERTED_ID)
    }

    override suspend fun update(
        permissionTrack: PermissionTrack,
        sqlClient: SqlClient
    ) {
        val query =
            "UPDATE `${getTablePrefix() + tableName}` SET `description` = ?, `groupIds` = ?, `updatedAt` = ? WHERE `id` = ?"

        sqlClient
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    permissionTrack.description,
                    JsonArray(permissionTrack.groupIds).encode(),
                    permissionTrack.updatedAt,
                    permissionTrack.id
                )
            ).coAwait()
    }

    override suspend fun getAll(sqlClient: SqlClient): List<PermissionTrack> {
        val query =
            "SELECT `id`, `name`, `description`, `groupIds`, `createdAt`, `updatedAt` FROM `${getTablePrefix() + tableName}`"

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute()
            .coAwait()

        return rows.toEntities()
    }
}

