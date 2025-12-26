package com.panomc.platform.db.migration

import com.panomc.platform.annotation.Migration
import com.panomc.platform.db.DatabaseMigration
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.sqlclient.RowSet
import io.vertx.sqlclient.SqlClient
import io.vertx.sqlclient.Tuple

@Migration
class DatabaseMigration18to19 : DatabaseMigration(
    18,
    19,
    "Add permission_track and permission_node tables, extend permission_group, migrate admin users, drop user.permissionGroupId, add default group."
) {
    override val handlers: List<suspend (SqlClient) -> Unit> = listOf(
        createPermissionTrackTable(),
        createPermissionNodeTable(),
        extendPermissionGroupTable(),
        migrateAdminUsersToPermissionNodesAndDropUserPermissionGroupId(),
        createDefaultGroupIfMissing(),
        addDefaultPermissionNodes(),
        dropLegacyPermissionTableIfExists(),
        dropLegacyPermissionGroupPermsTableIfExists()
    )

    private fun createPermissionTrackTable(): suspend (sqlClient: SqlClient) -> Unit = { sqlClient ->
        val query = """
            CREATE TABLE IF NOT EXISTS `${getTablePrefix()}permission_track` (
              `id` bigint NOT NULL AUTO_INCREMENT,
              `name` varchar(128) NOT NULL UNIQUE,
              `description` text NOT NULL,
              `groupIds` mediumtext NOT NULL,
              `createdAt` BIGINT(20) NOT NULL,
              `updatedAt` BIGINT(20) NOT NULL,
              PRIMARY KEY (`id`)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Permission Track table.';
        """.trimIndent()

        sqlClient.preparedQuery(query).execute().coAwait()
    }

    private fun createPermissionNodeTable(): suspend (sqlClient: SqlClient) -> Unit = { sqlClient ->
        val query = """
            CREATE TABLE IF NOT EXISTS `${getTablePrefix()}permission_node` (
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
        """.trimIndent()

        sqlClient.preparedQuery(query).execute().coAwait()
    }

    private fun extendPermissionGroupTable(): suspend (sqlClient: SqlClient) -> Unit = { sqlClient ->
        val table = "${getTablePrefix()}permission_group"

        // add new columns
        sqlClient.preparedQuery(
            """
                ALTER TABLE `$table`
                ADD COLUMN `displayName` varchar(64) NULL,
                ADD COLUMN `createdAt` BIGINT(20) NULL,
                ADD COLUMN `updatedAt` BIGINT(20) NULL;
            """.trimIndent()
        ).execute().coAwait()

        val now = System.currentTimeMillis()

        // backfill new columns
        sqlClient.preparedQuery(
            """
                UPDATE `$table`
                SET
                  `displayName` = COALESCE(`displayName`, `name`),
                  `createdAt` = COALESCE(`createdAt`, ?),
                  `updatedAt` = COALESCE(`updatedAt`, ?);
            """.trimIndent()
        ).execute(Tuple.of(now, now)).coAwait()

        // enforce not null and defaults
        sqlClient.preparedQuery(
            """
                ALTER TABLE `$table`
                MODIFY `displayName` varchar(64) NOT NULL,
                MODIFY `createdAt` BIGINT(20) NOT NULL,
                MODIFY `updatedAt` BIGINT(20) NOT NULL;
            """.trimIndent()
        ).execute().coAwait()

        sqlClient.preparedQuery(
            """
                UPDATE `$table`
                SET `updatedAt` = ?
                WHERE `name` = 'admin';
            """.trimIndent()
        ).execute(Tuple.of(now)).coAwait()
    }

    private fun migrateAdminUsersToPermissionNodesAndDropUserPermissionGroupId(): suspend (sqlClient: SqlClient) -> Unit =
        { sqlClient ->
            val prefix = getTablePrefix()

            // find admin group id
            val adminIdRows: RowSet<io.vertx.sqlclient.Row> = sqlClient
                .preparedQuery("SELECT `id` FROM `${prefix}permission_group` WHERE `name` = 'admin' LIMIT 1")
                .execute()
                .coAwait()

            if (adminIdRows.size() > 0) {
                val adminGroupId = adminIdRows.first().getLong("id")
                val now = System.currentTimeMillis()

                // insert permission nodes for users in admin group
                sqlClient
                    .preparedQuery(
                        """
                            INSERT INTO `${prefix}permission_node`
                            (`holderType`, `holderId`, `node`, `active`, `context`, `expiresAt`, `createdAt`, `updatedAt`)
                            SELECT 'USER', u.`id`, 'group.admin', 1, '{}', NULL, ?, ?
                            FROM `${prefix}user` u
                            WHERE u.`permissionGroupId` = ?;
                        """.trimIndent()
                    )
                    .execute(Tuple.of(now, now, adminGroupId))
                    .coAwait()
            }

            // drop permissionGroupId column
            sqlClient
                .preparedQuery(
                    """
                        ALTER TABLE `${prefix}user`
                        DROP COLUMN `permissionGroupId`;
                    """.trimIndent()
                )
                .execute()
                .coAwait()
        }

    private fun createDefaultGroupIfMissing(): suspend (sqlClient: SqlClient) -> Unit = { sqlClient ->
        val table = "${getTablePrefix()}permission_group"
        val now = System.currentTimeMillis()

        sqlClient
            .preparedQuery(
                """
                    INSERT INTO `$table` (`name`, `displayName`, `createdAt`, `updatedAt`)
                    SELECT 'default', 'default', ?, ?
                    WHERE NOT EXISTS (SELECT 1 FROM `$table` WHERE `name` = 'default');
                """.trimIndent()
            )
            .execute(Tuple.of(now, now))
            .coAwait()
    }

    private fun addDefaultPermissionNodes(): suspend (sqlClient: SqlClient) -> Unit = { sqlClient ->
        val prefix = getTablePrefix()
        val now = System.currentTimeMillis()

        suspend fun insertNodesForGroup(groupName: String, nodes: List<String>) {
            val groupIdRows = sqlClient.preparedQuery("SELECT `id` FROM `${prefix}permission_group` WHERE `name` = ?").execute(Tuple.of(groupName)).coAwait()
            if (groupIdRows.size() == 0) return
            val groupId = groupIdRows.first().getLong("id")

            nodes.forEach { node ->
                sqlClient.preparedQuery(
                    """
                    INSERT INTO `${prefix}permission_node` (`holderType`, `holderId`, `node`, `active`, `context`, `createdAt`, `updatedAt`)
                    SELECT 'GROUP', ?, ?, 1, '{}', ?, ?
                    WHERE NOT EXISTS (SELECT 1 FROM `${prefix}permission_node` WHERE `holderType` = 'GROUP' AND `holderId` = ? AND `node` = ?)
                """.trimIndent()
                ).execute(Tuple.of(groupId, node, now, now, groupId, node)).coAwait()
            }
        }

        insertNodesForGroup("admin", listOf("*", "weight.100", "group.default"))
        insertNodesForGroup("default", listOf("weight.10"))
    }

    // Legacy cleanup: remove pre-19 permission tables (not used after permission_node/permission_group weight system).
    private fun dropLegacyPermissionTableIfExists(): suspend (sqlClient: SqlClient) -> Unit = { sqlClient ->
        val table = "${getTablePrefix()}permission"
        sqlClient.preparedQuery("DROP TABLE IF EXISTS `$table`;").execute().coAwait()
    }

    private fun dropLegacyPermissionGroupPermsTableIfExists(): suspend (sqlClient: SqlClient) -> Unit = { sqlClient ->
        val table = "${getTablePrefix()}permission_group_perms"
        sqlClient.preparedQuery("DROP TABLE IF EXISTS `$table`;").execute().coAwait()
    }
}

