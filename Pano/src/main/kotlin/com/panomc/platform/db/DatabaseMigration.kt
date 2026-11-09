package com.panomc.platform.db

import com.panomc.platform.Main.Companion.applicationContext
import com.panomc.platform.db.model.SchemeVersion
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.sqlclient.SqlClient
import io.vertx.sqlclient.Tuple

abstract class DatabaseMigration(val from: Int, val to: Int, val info: String) {
    abstract val handlers: List<suspend (sqlClient: SqlClient) -> Unit>

    private val databaseManager: DatabaseManager by lazy {
        applicationContext.getBean(DatabaseManager::class.java)
    }

    fun isMigratable(version: Int) = version == from

    suspend fun migrate(sqlClient: SqlClient) {
        handlers.forEach {
            it.invoke(sqlClient)
        }
    }

    suspend fun updateSchemeVersion(
        sqlClient: SqlClient
    ) {
        databaseManager.schemeVersionDao.add(
            sqlClient,
            SchemeVersion(key = to.toString(), extra = info)
        )
    }

    fun getTablePrefix() = databaseManager.getTablePrefix()

    // Re-runnable building blocks. MariaDB commits DDL at once, so a migration that stopped half way is run again
    // from its first handler on the next start: every step must see what is already there and skip it. They read
    // information_schema instead of using MariaDB's `IF [NOT] EXISTS` on columns, so they also work on MySQL.
    // `table` is the full table name, prefix included.

    protected suspend fun hasColumn(sqlClient: SqlClient, table: String, column: String): Boolean =
        sqlClient
            .preparedQuery(
                "SELECT COUNT(*) FROM `information_schema`.`COLUMNS` " +
                        "WHERE `TABLE_SCHEMA` = DATABASE() AND `TABLE_NAME` = ? AND `COLUMN_NAME` = ?"
            )
            .execute(Tuple.of(table, column))
            .coAwait()
            .first()
            .getLong(0) > 0

    protected suspend fun hasIndex(sqlClient: SqlClient, table: String, index: String): Boolean =
        sqlClient
            .preparedQuery(
                "SELECT COUNT(*) FROM `information_schema`.`STATISTICS` " +
                        "WHERE `TABLE_SCHEMA` = DATABASE() AND `TABLE_NAME` = ? AND `INDEX_NAME` = ?"
            )
            .execute(Tuple.of(table, index))
            .coAwait()
            .first()
            .getLong(0) > 0

    /** True when a unique index covers exactly [column] (whatever its name). */
    protected suspend fun hasUniqueIndexOn(sqlClient: SqlClient, table: String, column: String): Boolean =
        sqlClient
            .preparedQuery(
                "SELECT COUNT(*) FROM (SELECT `INDEX_NAME` FROM `information_schema`.`STATISTICS` " +
                        "WHERE `TABLE_SCHEMA` = DATABASE() AND `TABLE_NAME` = ? AND `NON_UNIQUE` = 0 " +
                        "GROUP BY `INDEX_NAME` HAVING COUNT(*) = 1 AND MAX(`COLUMN_NAME`) = ?) AS `unique_indexes`"
            )
            .execute(Tuple.of(table, column))
            .coAwait()
            .first()
            .getLong(0) > 0

    /**
     * Adds the [columns] (name to definition, e.g. `"createdAt" to "BIGINT(20) DEFAULT 0"`) that [table] does not
     * have yet, in one ALTER TABLE so the table is rewritten once.
     */
    protected suspend fun addMissingColumns(sqlClient: SqlClient, table: String, vararg columns: Pair<String, String>) {
        val missing = columns.filter { (name, _) -> !hasColumn(sqlClient, table, name) }

        if (missing.isEmpty()) {
            return
        }

        val query = "ALTER TABLE `$table` " + missing.joinToString(", ") { (name, definition) ->
            "ADD COLUMN `$name` $definition"
        }

        sqlClient.query(query).execute().coAwait()
    }

    protected suspend fun dropColumnIfPresent(sqlClient: SqlClient, table: String, column: String) {
        if (hasColumn(sqlClient, table, column)) {
            sqlClient.query("ALTER TABLE `$table` DROP COLUMN `$column`").execute().coAwait()
        }
    }

    /** Renames [from] to [to] unless that already happened (needs MySQL 8.0+ or MariaDB 10.5+). */
    protected suspend fun renameColumnIfPresent(sqlClient: SqlClient, table: String, from: String, to: String) {
        if (hasColumn(sqlClient, table, from) && !hasColumn(sqlClient, table, to)) {
            sqlClient.query("ALTER TABLE `$table` RENAME COLUMN `$from` TO `$to`").execute().coAwait()
        }
    }

    /** Runs [statement] (an `ALTER TABLE ... ADD [UNIQUE] KEY` for [index]) unless [table] already has that index. */
    protected suspend fun addIndexIfMissing(sqlClient: SqlClient, table: String, index: String, statement: String) {
        if (!hasIndex(sqlClient, table, index)) {
            sqlClient.query(statement).execute().coAwait()
        }
    }
}
