package com.panomc.platform.db.migration

import com.panomc.platform.annotation.Migration
import com.panomc.platform.db.DatabaseMigration
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.sqlclient.SqlClient

/**
 * A ticket category says what its "new ticket" form shows: `ticket_category.form` holds whether the title and the
 * description are asked for and their placeholders (empty: everything shown, the theme's placeholders).
 */
@Migration
class DatabaseMigration71to72 : DatabaseMigration(
    71,
    72,
    "Ticket category form options (title / description on or off, placeholders)"
) {
    override val handlers: List<suspend (SqlClient) -> Unit> = listOf(
        { sqlClient ->
            sqlClient.query("ALTER TABLE `${getTablePrefix()}ticket_category` ADD COLUMN IF NOT EXISTS `form` TEXT NULL")
                .execute().coAwait()
        }
    )
}
