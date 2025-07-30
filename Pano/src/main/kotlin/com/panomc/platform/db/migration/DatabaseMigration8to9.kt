package com.panomc.platform.db.migration

import com.panomc.platform.annotation.Migration
import com.panomc.platform.db.DatabaseMigration
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.sqlclient.SqlClient

@Migration
class DatabaseMigration8to9 : DatabaseMigration(8, 9, "Rename addon hash table to resource hash.") {
    override val handlers: List<suspend (SqlClient) -> Unit> = listOf(
        renameAddonHashTableToResourceHash(),
    )

    private fun renameAddonHashTableToResourceHash(): suspend (sqlClient: SqlClient) -> Unit =
        { sqlClient: SqlClient ->
            val query = "RENAME TABLE `${getTablePrefix()}addon_hash` TO `${getTablePrefix()}resource_hash`;"

            sqlClient
                .preparedQuery(query)
                .execute()
                .coAwait()
        }
}