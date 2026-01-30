package com.panomc.platform.db.migration

import com.panomc.platform.annotation.Migration
import com.panomc.platform.db.DatabaseMigration
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.sqlclient.SqlClient

@Migration
class DatabaseMigration24to25 : DatabaseMigration(24, 25, "Add ipAddress and userAgent columns to token table.") {
    override val handlers: List<suspend (SqlClient) -> Unit> = listOf(
        { sqlClient ->
            sqlClient.query("ALTER TABLE `${getTablePrefix()}token` ADD COLUMN `ipAddress` varchar(64) DEFAULT NULL").execute().coAwait()
            sqlClient.query("ALTER TABLE `${getTablePrefix()}token` ADD COLUMN `userAgent` text DEFAULT NULL").execute().coAwait()
        }
    )
}
