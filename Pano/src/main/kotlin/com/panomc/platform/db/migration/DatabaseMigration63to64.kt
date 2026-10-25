package com.panomc.platform.db.migration

import com.panomc.platform.annotation.Migration
import com.panomc.platform.db.DatabaseMigration
import com.panomc.platform.db.implementation.PostAutosaveDaoImpl
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.sqlclient.SqlClient

/** Adds `post_autosave`, the unsaved changes the post editor keeps while an admin writes. */
@Migration
class DatabaseMigration63to64 : DatabaseMigration(
    63,
    64,
    "Add the post_autosave table"
) {
    override val handlers: List<suspend (SqlClient) -> Unit> = listOf(
        { sqlClient: SqlClient ->
            sqlClient
                .query(PostAutosaveDaoImpl.createTableQuery("${getTablePrefix()}post_autosave"))
                .execute()
                .coAwait()
        }
    )
}
