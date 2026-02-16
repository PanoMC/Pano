package com.panomc.platform.db.migration

import com.panomc.platform.annotation.Migration
import com.panomc.platform.db.DatabaseMigration
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.sqlclient.SqlClient

@Migration
class DatabaseMigration25to26 : DatabaseMigration(25, 26, "Add link code to user table") {
    override val handlers: List<suspend (sqlClient: SqlClient) -> Unit> = listOf(
        { sqlClient ->
            sqlClient
                .query("ALTER TABLE `${getTablePrefix()}user` ADD `linkCode` VARCHAR(6) NULL")
                .execute()
                .coAwait()
        },
        { sqlClient ->
            sqlClient
                .query("ALTER TABLE `${getTablePrefix()}user` ADD `linkCodeCreatedAt` BIGINT NULL")
                .execute()
                .coAwait()
        },
        { sqlClient ->
            sqlClient
                .query("ALTER TABLE `${getTablePrefix()}User` MODIFY `password` VARCHAR(255) NULL")
                .execute()
                .coAwait()
        },
        { sqlClient ->
            // Email is already nullable in User entity, but let's ensure DB allows it if not already
            sqlClient
                .query("ALTER TABLE `${getTablePrefix()}user` MODIFY `email` VARCHAR(255) NULL")
                .execute()
                .coAwait()
        }
    )
}
