package com.panomc.platform.db.migration

import com.panomc.platform.annotation.Migration
import com.panomc.platform.db.DatabaseMigration
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.sqlclient.SqlClient
import io.vertx.sqlclient.Tuple
import java.util.*

@Migration
class DatabaseMigration26to27 : DatabaseMigration(26, 27, "Generate offline UUIDs for users without mcUuid") {
    override val handlers: List<suspend (sqlClient: SqlClient) -> Unit> = listOf(
        { sqlClient ->
            val table = "${getTablePrefix()}user"

            // Find all users with empty or null mcUuid
            val rows = sqlClient
                .query("SELECT `id`, `username` FROM `$table` WHERE `mcUuid` IS NULL OR `mcUuid` = ''")
                .execute()
                .coAwait()

            for (row in rows) {
                val id = row.getLong("id")
                val username = row.getString("username")

                // Generate offline UUID using the same formula as cracked Minecraft servers:
                // UUID.nameUUIDFromBytes(("OfflinePlayer:" + username).getBytes("UTF-8"))
                val offlineUuid = UUID.nameUUIDFromBytes(
                    ("OfflinePlayer:$username").toByteArray(Charsets.UTF_8)
                ).toString()

                sqlClient
                    .preparedQuery("UPDATE `$table` SET `mcUuid` = ? WHERE `id` = ?")
                    .execute(Tuple.of(offlineUuid, id))
                    .coAwait()
            }
        }
    )
}
