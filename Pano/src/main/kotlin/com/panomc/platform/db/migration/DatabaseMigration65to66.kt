package com.panomc.platform.db.migration

import com.panomc.platform.annotation.Migration
import com.panomc.platform.db.DatabaseMigration
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.sqlclient.SqlClient

/** SM-PB — a managed server backup remembers the id of its copy at Pano Backup (`panoBackupId`). */
@Migration
class DatabaseMigration65to66 : DatabaseMigration(
    65,
    66,
    "Add the Pano Backup copy id to server backups"
) {
    override val handlers: List<suspend (SqlClient) -> Unit> = listOf(
        { sqlClient: SqlClient ->
            sqlClient
                .query(
                    "ALTER TABLE `${getTablePrefix()}server_backup` " +
                            "ADD COLUMN IF NOT EXISTS `panoBackupId` varchar(64) DEFAULT NULL"
                )
                .execute()
                .coAwait()
        }
    )
}
