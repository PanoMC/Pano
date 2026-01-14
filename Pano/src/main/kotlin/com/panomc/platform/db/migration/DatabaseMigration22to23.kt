package com.panomc.platform.db.migration

import com.panomc.platform.annotation.Migration
import com.panomc.platform.db.DatabaseMigration
import io.vertx.sqlclient.SqlClient

@Migration
class DatabaseMigration22to23 : DatabaseMigration(22, 23, "Add SSL settings support to the platform.") {
    override val handlers: List<suspend (SqlClient) -> Unit> = listOf(
        // No specific table changes needed as settings are stored in config file,
        // but this migration marks the version where SSL support was introduced.
    )
}
