package com.panomc.platform.db.migration

import com.panomc.platform.annotation.Migration
import com.panomc.platform.db.DatabaseMigration
import com.panomc.platform.db.implementation.TicketTemplateDaoImpl
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.sqlclient.SqlClient

/**
 * Ready replies for tickets: adds `ticket_template`, the auto-reply template of a ticket category, and marks the
 * ticket messages the site sends by itself (`system`).
 */
@Migration
class DatabaseMigration64to65 : DatabaseMigration(
    64,
    65,
    "Add ticket templates, category auto-replies and system ticket messages"
) {
    override val handlers: List<suspend (SqlClient) -> Unit> = listOf(
        { sqlClient: SqlClient ->
            sqlClient
                .query(TicketTemplateDaoImpl.createTableQuery("${getTablePrefix()}ticket_template"))
                .execute()
                .coAwait()
        },
        { sqlClient: SqlClient ->
            sqlClient
                .query(
                    "ALTER TABLE `${getTablePrefix()}ticket_category` " +
                            "ADD COLUMN IF NOT EXISTS `autoReplyTemplateId` bigint NOT NULL DEFAULT -1"
                )
                .execute()
                .coAwait()
        },
        { sqlClient: SqlClient ->
            sqlClient
                .query(
                    "ALTER TABLE `${getTablePrefix()}ticket_message` " +
                            "ADD COLUMN IF NOT EXISTS `system` TINYINT(1) NOT NULL DEFAULT 0"
                )
                .execute()
                .coAwait()
        }
    )
}
