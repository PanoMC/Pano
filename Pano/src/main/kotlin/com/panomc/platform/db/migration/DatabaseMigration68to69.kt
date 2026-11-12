package com.panomc.platform.db.migration

import com.panomc.platform.annotation.Migration
import com.panomc.platform.db.DatabaseMigration
import com.panomc.platform.db.implementation.TicketCategoryFieldSetDaoImpl
import com.panomc.platform.db.implementation.TicketFieldSetDaoImpl
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.sqlclient.SqlClient

/** Ticket categories get custom fields, tickets keep the values their player entered. */
@Migration
class DatabaseMigration68to69 : DatabaseMigration(
    68,
    69,
    "Custom fields of ticket categories"
) {
    override val handlers: List<suspend (SqlClient) -> Unit> = listOf(
        { sqlClient ->
            sqlClient.query(TicketCategoryFieldSetDaoImpl.createTableQuery("${getTablePrefix()}ticket_category_field_set"))
                .execute().coAwait()
        },
        { sqlClient ->
            sqlClient.query(TicketFieldSetDaoImpl.createTableQuery("${getTablePrefix()}ticket_field_set"))
                .execute().coAwait()
        }
    )
}
