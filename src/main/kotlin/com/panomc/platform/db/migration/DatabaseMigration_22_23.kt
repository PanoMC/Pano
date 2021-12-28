package com.panomc.platform.db.migration

import com.panomc.platform.db.DatabaseMigration
import io.vertx.core.AsyncResult
import io.vertx.sqlclient.SqlConnection

@Suppress("ClassName")
class DatabaseMigration_22_23 : DatabaseMigration() {
    override val FROM_SCHEME_VERSION = 22
    override val SCHEME_VERSION = 23
    override val SCHEME_VERSION_INFO =
        "Rename post text colum name."

    override val handlers: List<(sqlConnection: SqlConnection, handler: (asyncResult: AsyncResult<*>) -> Unit) -> Unit> =
        listOf(
            renamePostTextColumnName(),
        )

    private fun renamePostTextColumnName(): (sqlConnection: SqlConnection, handler: (asyncResult: AsyncResult<*>) -> Unit) -> Unit =
        { sqlConnection, handler ->
            sqlConnection
                .query("ALTER TABLE `${getTablePrefix()}post` RENAME COLUMN `post` TO `text`;")
                .execute {
                    handler.invoke(it)
                }
        }
}