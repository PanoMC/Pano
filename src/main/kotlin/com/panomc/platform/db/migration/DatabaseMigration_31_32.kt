package com.panomc.platform.db.migration

import com.panomc.platform.annotation.Migration
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.DatabaseMigration
import io.vertx.kotlin.coroutines.await
import io.vertx.sqlclient.SqlConnection

@Suppress("ClassName")
@Migration
class DatabaseMigration_31_32(databaseManager: DatabaseManager) : DatabaseMigration(databaseManager) {
    override val FROM_SCHEME_VERSION = 31
    override val SCHEME_VERSION = 32
    override val SCHEME_VERSION_INFO =
        "Drop image field & add thumbnail_url field in post table."

    override val handlers: List<suspend (sqlConnection: SqlConnection) -> Unit> =
        listOf(
            dropImageFieldInPostTable(),
            addThumbnailUrlToPostTable()
        )

    private fun dropImageFieldInPostTable(): suspend (sqlConnection: SqlConnection) -> Unit =
        { sqlConnection: SqlConnection ->
            sqlConnection
                .query("ALTER TABLE `${getTablePrefix()}post` DROP COLUMN `image`;")
                .execute()
                .await()
        }

    private fun addThumbnailUrlToPostTable(): suspend (sqlConnection: SqlConnection) -> Unit =
        { sqlConnection: SqlConnection ->
            sqlConnection
                .query("ALTER TABLE `${getTablePrefix()}post` ADD `thumbnail_url` mediumtext DEFAULT '';")
                .execute()
                .await()
        }
}