package com.panomc.platform.db.migration

import com.panomc.platform.annotation.Migration
import com.panomc.platform.db.DatabaseMigration
import com.panomc.platform.db.implementation.MailTemplateDaoImpl
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.sqlclient.SqlClient

/**
 * Adds `mail_template`: the panel's overrides of the bundled mail templates (one row per mail × variant ×
 * language, plus the shared layout as `core / _layout / default / *`).
 */
@Migration
class DatabaseMigration61to62 : DatabaseMigration(
    61,
    62,
    "Add the mail_template table"
) {
    override val handlers: List<suspend (SqlClient) -> Unit> = listOf(
        { sqlClient: SqlClient ->
            sqlClient
                .query(MailTemplateDaoImpl.createTableQuery("${getTablePrefix()}mail_template"))
                .execute()
                .coAwait()
        }
    )
}
