package com.panomc.platform.db.migration

import com.panomc.platform.annotation.Migration
import com.panomc.platform.db.DatabaseMigration
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.sqlclient.SqlClient
import io.vertx.sqlclient.Tuple

/**
 * A locale code is unique. Nothing enforced it, so two set-ups finishing at once or a panel user could leave two
 * rows with the same code (compared case-insensitively, like the column), and the language then counted as
 * missing. The oldest row of each code stays; the translations of the others move to it unless it already has
 * that text, and the rest go with their row. Then the column gets its unique key.
 */
@Migration
class DatabaseMigration66to67 : DatabaseMigration(
    66,
    67,
    "Make locale codes unique"
) {
    override val handlers: List<suspend (SqlClient) -> Unit> = listOf(
        removeDuplicateLocales(),
        addUniqueCodeKey()
    )

    private fun removeDuplicateLocales(): suspend (sqlClient: SqlClient) -> Unit = { sqlClient: SqlClient ->
        val locale = "${getTablePrefix()}locale"
        val translation = "${getTablePrefix()}translation"

        // `l`.`code` = `k`.`code` uses the column's collation, so "EN-us" and "en-US" are one code.
        val duplicates = sqlClient
            .query(
                "SELECT `l`.`id`, (SELECT MIN(`k`.`id`) FROM `$locale` `k` WHERE `k`.`code` = `l`.`code`) AS `keep` " +
                        "FROM `$locale` `l`"
            )
            .execute()
            .coAwait()
            .map { it.getLong(0) to it.getLong(1) }
            .filter { (id, keep) -> id != keep }

        duplicates.forEach { (id, keep) ->
            sqlClient
                .preparedQuery(
                    "UPDATE `$translation` `t` SET `t`.`localeId` = ? WHERE `t`.`localeId` = ? AND NOT EXISTS " +
                            "(SELECT 1 FROM (SELECT `type`, `key` FROM `$translation` WHERE `localeId` = ?) `o` " +
                            "WHERE `o`.`type` = `t`.`type` AND `o`.`key` = `t`.`key`)"
                )
                .execute(Tuple.of(keep.toString(), id.toString(), keep.toString()))
                .coAwait()

            sqlClient
                .preparedQuery("DELETE FROM `$translation` WHERE `localeId` = ?")
                .execute(Tuple.of(id.toString()))
                .coAwait()

            sqlClient
                .preparedQuery("DELETE FROM `$locale` WHERE `id` = ?")
                .execute(Tuple.of(id))
                .coAwait()
        }
    }

    private fun addUniqueCodeKey(): suspend (sqlClient: SqlClient) -> Unit = { sqlClient: SqlClient ->
        val locale = "${getTablePrefix()}locale"

        if (!hasUniqueIndexOn(sqlClient, locale, "code")) {
            sqlClient.query("ALTER TABLE `$locale` ADD UNIQUE KEY `code` (`code`)").execute().coAwait()
        }
    }
}
