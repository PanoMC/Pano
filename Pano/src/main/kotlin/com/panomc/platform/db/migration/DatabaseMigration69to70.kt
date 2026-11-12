package com.panomc.platform.db.migration

import com.panomc.platform.annotation.Migration
import com.panomc.platform.db.DatabaseMigration
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.sqlclient.SqlClient

/** Translations hold whole posts now: `translation.value` grows from `TEXT` (64 KB) to `MEDIUMTEXT`. */
@Migration
class DatabaseMigration69to70 : DatabaseMigration(
    69,
    70,
    "Longer translations (posts)"
) {
    override val handlers: List<suspend (SqlClient) -> Unit> = listOf(
        { sqlClient ->
            sqlClient.query("ALTER TABLE `${getTablePrefix()}translation` MODIFY `value` MEDIUMTEXT NOT NULL").execute().coAwait()
        }
    )
}
