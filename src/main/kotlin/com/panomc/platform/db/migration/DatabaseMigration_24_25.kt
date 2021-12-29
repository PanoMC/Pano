package com.panomc.platform.db.migration

import com.panomc.platform.db.DatabaseMigration
import io.vertx.core.AsyncResult
import io.vertx.sqlclient.SqlConnection

@Suppress("ClassName")
class DatabaseMigration_24_25 : DatabaseMigration() {
    override val FROM_SCHEME_VERSION = 24
    override val SCHEME_VERSION = 25
    override val SCHEME_VERSION_INFO =
        "Drop 'public_key' and 'secret_key' columns in user table."

    override val handlers: List<(sqlConnection: SqlConnection, handler: (asyncResult: AsyncResult<*>) -> Unit) -> Unit> =
        listOf(
            dropPublicKeyFromUserTable(),
            dropSecretKeyFromUserTable(),
        )

    private fun dropPublicKeyFromUserTable(): (sqlConnection: SqlConnection, handler: (asyncResult: AsyncResult<*>) -> Unit) -> Unit =
        { sqlConnection, handler ->
            sqlConnection
                .query("ALTER TABLE `${getTablePrefix()}user` DROP COLUMN `public_key`;")
                .execute {
                    handler.invoke(it)
                }
        }

    private fun dropSecretKeyFromUserTable(): (sqlConnection: SqlConnection, handler: (asyncResult: AsyncResult<*>) -> Unit) -> Unit =
        { sqlConnection, handler ->
            sqlConnection
                .query("ALTER TABLE `${getTablePrefix()}user` DROP COLUMN `secret_key`;")
                .execute {
                    handler.invoke(it)
                }
        }
}