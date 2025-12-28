package com.panomc.platform.db.migration

import com.panomc.platform.annotation.Migration
import com.panomc.platform.db.DatabaseMigration
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.sqlclient.SqlClient
import io.vertx.sqlclient.Tuple

@Migration
class DatabaseMigration19to20 : DatabaseMigration(
    19,
    20,
    "Backfill group displayName nodes (displayname.<value>) into permission_node table."
) {
    override val handlers: List<suspend (SqlClient) -> Unit> = listOf(
        addDisplayNameNodesForAllGroupsIfMissing(),
        ensureDisplayNameNodesForSeedGroups()
    )

    private fun addDisplayNameNodesForAllGroupsIfMissing(): suspend (sqlClient: SqlClient) -> Unit = { sqlClient ->
        val prefix = getTablePrefix()
        val now = System.currentTimeMillis()

        val rows = sqlClient
            .preparedQuery("SELECT `id`, `name`, `displayName` FROM `${prefix}permission_group`")
            .execute()
            .coAwait()

        rows.forEach { row ->
            val groupId = row.getLong("id")
            val groupName = row.getString("name")
            val displayName = row.getString("displayName") ?: groupName
            val node = "displayname.$displayName"

            sqlClient
                .preparedQuery(
                    """
                        INSERT INTO `${prefix}permission_node`
                        (`holderType`, `holderId`, `node`, `active`, `context`, `expiresAt`, `createdAt`, `updatedAt`)
                        SELECT 'GROUP', ?, ?, 1, '{}', NULL, ?, ?
                        WHERE NOT EXISTS (
                            SELECT 1 FROM `${prefix}permission_node`
                            WHERE `holderType` = 'GROUP' AND `holderId` = ? AND `node` = ?
                        );
                    """.trimIndent()
                )
                .execute(Tuple.of(groupId, node, now, now, groupId, node))
                .coAwait()
        }
    }

    /**
     * Ensure displayname nodes exist for the two seed groups created/used by the permissions system.
     * (admin + default)
     */
    private fun ensureDisplayNameNodesForSeedGroups(): suspend (sqlClient: SqlClient) -> Unit = { sqlClient ->
        val prefix = getTablePrefix()
        val now = System.currentTimeMillis()

        suspend fun ensureForGroupName(groupName: String) {
            val rows = sqlClient
                .preparedQuery("SELECT `id`, `displayName` FROM `${prefix}permission_group` WHERE `name` = ? LIMIT 1")
                .execute(Tuple.of(groupName))
                .coAwait()

            if (rows.size() == 0) return

            val groupId = rows.first().getLong("id")
            val displayName = rows.first().getString("displayName") ?: groupName
            val node = "displayname.$displayName"

            sqlClient
                .preparedQuery(
                    """
                        INSERT INTO `${prefix}permission_node`
                        (`holderType`, `holderId`, `node`, `active`, `context`, `expiresAt`, `createdAt`, `updatedAt`)
                        SELECT 'GROUP', ?, ?, 1, '{}', NULL, ?, ?
                        WHERE NOT EXISTS (
                            SELECT 1 FROM `${prefix}permission_node`
                            WHERE `holderType` = 'GROUP' AND `holderId` = ? AND `node` = ?
                        );
                    """.trimIndent()
                )
                .execute(Tuple.of(groupId, node, now, now, groupId, node))
                .coAwait()
        }

        ensureForGroupName("admin")
        ensureForGroupName("default")
    }
}


