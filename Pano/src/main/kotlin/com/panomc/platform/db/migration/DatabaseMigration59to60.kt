package com.panomc.platform.db.migration

import com.panomc.platform.annotation.Migration
import com.panomc.platform.db.DatabaseMigration
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.sqlclient.SqlClient

/**
 * Adds `locale.enabled`: a language that is turned off stays in the panel (and keeps its translations) but is
 * no longer offered to visitors. Every language that exists is on.
 */
@Migration
class DatabaseMigration59to60 : DatabaseMigration(
    59,
    60,
    "Add the enabled column to the locale table"
) {
    override val handlers: List<suspend (SqlClient) -> Unit> = listOf(
        { sqlClient: SqlClient ->
            sqlClient
                .query(
                    "ALTER TABLE `${getTablePrefix()}locale` ADD COLUMN IF NOT EXISTS `enabled` TINYINT(1) NOT NULL DEFAULT 1"
                )
                .execute()
                .coAwait()
        }
    )
}
