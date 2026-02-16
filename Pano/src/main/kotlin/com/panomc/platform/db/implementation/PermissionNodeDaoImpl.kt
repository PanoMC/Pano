package com.panomc.platform.db.implementation

import com.panomc.platform.annotation.Dao
import com.panomc.platform.db.dao.PermissionNodeDao
import com.panomc.platform.db.model.PermissionGroup
import com.panomc.platform.db.model.PermissionNode
import com.panomc.platform.util.TextUtil.convertToSnakeCase
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.mysqlclient.MySQLClient
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.RowSet
import io.vertx.sqlclient.SqlClient
import io.vertx.sqlclient.Tuple

@Dao
class PermissionNodeDaoImpl : PermissionNodeDao() {

    override suspend fun init(sqlClient: SqlClient) {
        sqlClient
            .query(
                """
                            CREATE TABLE IF NOT EXISTS `${getTablePrefix() + tableName}` (
                              `id` bigint NOT NULL AUTO_INCREMENT,
                              `holderType` varchar(32) NOT NULL,
                              `holderId` bigint NOT NULL,
                              `node` varchar(255) NOT NULL,
                              `active` tinyint(1) NOT NULL DEFAULT 0,
                              `context` mediumtext NOT NULL,
                              `expiresAt` bigint NULL,
                              `createdAt` BIGINT(20) NOT NULL,
                              `updatedAt` BIGINT(20) NOT NULL,
                              PRIMARY KEY (`id`)
                            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Permission Node table.';
                        """
            )
            .execute()
            .coAwait()

        addPermissionNodeForGroup("admin", "*", sqlClient)
        addPermissionNodeForGroup("admin", "weight.100", sqlClient)
        addPermissionNodeForGroup("admin", "group.default", sqlClient)
        addPermissionNodeForGroup("admin", "displayname.admin", sqlClient)

        addPermissionNodeForGroup("default", "weight.10", sqlClient)
        addPermissionNodeForGroup("default", "displayname.Player", sqlClient)
    }

    private suspend fun addPermissionNodeForGroup(
        groupName: String,
        node: String,
        sqlClient: SqlClient
    ) {
        val groupIdQuery = "SELECT `id` FROM `${getTablePrefix() + PermissionGroup::class.simpleName!!.convertToSnakeCase().lowercase()}` WHERE `name` = ?"
        val groupIdRows = sqlClient.preparedQuery(groupIdQuery).execute(Tuple.of(groupName)).coAwait()
        if (groupIdRows.size() == 0) return
        val groupId = groupIdRows.first().getLong(0)

        val checkQuery =
            "SELECT COUNT(*) FROM `${getTablePrefix() + tableName}` WHERE `holderType` = ? AND `holderId` = ? AND `node` = ?"
        val checkRows = sqlClient.preparedQuery(checkQuery).execute(
            Tuple.of(
                PermissionNode.Companion.HolderType.GROUP.name,
                groupId,
                node
            )
        ).coAwait()
        if (checkRows.first().getLong(0) > 0L) return

        add(
            PermissionNode(
                holderType = PermissionNode.Companion.HolderType.GROUP,
                holderId = groupId,
                node = node,
                active = true
            ),
            sqlClient
        )
    }

    override suspend fun add(
        permissionNode: PermissionNode,
        sqlClient: SqlClient
    ): Long {
        val query =
            "INSERT INTO `${getTablePrefix() + tableName}` (`holderType`, `holderId`, `node`, `active`, `context`, `expiresAt`, `createdAt`, `updatedAt`) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?)"

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    permissionNode.holderType.name,
                    permissionNode.holderId,
                    permissionNode.node,
                    permissionNode.active,
                    permissionNode.context.encode(),
                    permissionNode.expiresAt,
                    permissionNode.createdAt,
                    permissionNode.updatedAt
                )
            ).coAwait()

        return rows.property(MySQLClient.LAST_INSERTED_ID)
    }

    override suspend fun getPermissionNodes(sqlClient: SqlClient): List<PermissionNode> {
        val query =
            "SELECT `id`, `holderType`, `holderId`, `node`, `active`, `context`, `expiresAt`, `createdAt`, `updatedAt` FROM `${getTablePrefix() + tableName}`"

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute()
            .coAwait()

        return rows.toEntities()
    }

    override suspend fun deleteByIds(ids: List<Long>, sqlClient: SqlClient) {
        if (ids.isEmpty()) return

        val query = "DELETE FROM `${getTablePrefix() + tableName}` WHERE `id` IN (${ids.joinToString(",")})"

        sqlClient
            .query(query)
            .execute()
            .coAwait()
    }

    override suspend fun deleteByUserId(
        userId: Long,
        sqlClient: SqlClient
    ) {
        val query =
            "DELETE FROM `${getTablePrefix() + tableName}` WHERE `holderType` = ? AND `holderId` = ?"

        sqlClient
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    "USER", userId
                )
            )
            .coAwait()
    }
}

