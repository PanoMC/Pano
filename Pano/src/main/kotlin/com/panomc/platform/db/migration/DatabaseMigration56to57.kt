package com.panomc.platform.db.migration

import com.panomc.platform.annotation.Migration
import com.panomc.platform.db.DatabaseMigration
import com.panomc.platform.db.implementation.FrontendKeyDaoImpl
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.sqlclient.SqlClient

/**
 * Adds `frontend_key` (open front-end plan, doc 05 §3.2): the keys a front-end server sends as
 * `X-Pano-Frontend-Key`. Only the SHA-256 of each key is stored.
 */
@Migration
class DatabaseMigration56to57 : DatabaseMigration(
    56,
    57,
    "Add the frontend_key table"
) {
    override val handlers: List<suspend (SqlClient) -> Unit> = listOf(
        { sqlClient: SqlClient ->
            sqlClient
                .preparedQuery(FrontendKeyDaoImpl.createTableQuery("${getTablePrefix()}frontend_key"))
                .execute()
                .coAwait()
        }
    )
}
