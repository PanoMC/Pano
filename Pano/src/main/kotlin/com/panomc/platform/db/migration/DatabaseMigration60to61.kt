package com.panomc.platform.db.migration

import com.panomc.platform.annotation.Migration
import com.panomc.platform.db.DatabaseMigration
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.sqlclient.SqlClient

/**
 * Adds `ticket_category.enabled`: a category that is turned off keeps its tickets and stays in the panel, but no
 * new ticket can be opened in it. Every category that exists is on.
 */
@Migration
class DatabaseMigration60to61 : DatabaseMigration(
    60,
    61,
    "Add the enabled column to the ticket_category table"
) {
    override val handlers: List<suspend (SqlClient) -> Unit> = listOf(
        { sqlClient: SqlClient ->
            sqlClient
                .query(
                    "ALTER TABLE `${getTablePrefix()}ticket_category` ADD COLUMN IF NOT EXISTS `enabled` TINYINT(1) NOT NULL DEFAULT 1"
                )
                .execute()
                .coAwait()
        }
    )
}
