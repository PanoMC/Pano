package com.panomc.platform.db.migration

import com.panomc.platform.annotation.Migration
import com.panomc.platform.db.DatabaseMigration
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.sqlclient.SqlClient
import io.vertx.sqlclient.Tuple

/**
 * User dates that were stored as 0, which the panel showed as 1 January 1970:
 * - `registerDate` / `lastLoginDate` of AuthMe imports whose AuthMe row had no date: each takes the other one,
 *   the register date the migration time when both are missing;
 * - `lastActivityTime` of users never active on the site (created in the panel, imported, registered in game):
 *   it takes their last login.
 */
@Migration
class DatabaseMigration70to71 : DatabaseMigration(
    70,
    71,
    "User dates stored as 0 fall back to the other user dates"
) {
    override val handlers: List<suspend (SqlClient) -> Unit> = listOf(
        { sqlClient ->
            val table = "${getTablePrefix()}user"

            sqlClient.preparedQuery(
                "UPDATE `$table` SET `registerDate` = IF(`lastLoginDate` > 0, `lastLoginDate`, ?) WHERE `registerDate` <= 0"
            ).execute(Tuple.of(System.currentTimeMillis())).coAwait()

            sqlClient.query(
                "UPDATE `$table` SET `lastLoginDate` = `registerDate` WHERE `lastLoginDate` <= 0"
            ).execute().coAwait()

            sqlClient.query(
                "UPDATE `$table` SET `lastActivityTime` = `lastLoginDate` WHERE `lastActivityTime` <= 0"
            ).execute().coAwait()
        }
    )
}
