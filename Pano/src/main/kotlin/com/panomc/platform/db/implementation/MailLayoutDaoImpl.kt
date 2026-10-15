package com.panomc.platform.db.implementation

import com.panomc.platform.annotation.Dao
import com.panomc.platform.db.dao.MailLayoutDao
import com.panomc.platform.db.model.MailLayout
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.sqlclient.SqlClient
import io.vertx.sqlclient.Tuple

@Dao
class MailLayoutDaoImpl : MailLayoutDao() {
    private val table get() = getTablePrefix() + tableName

    override suspend fun init(sqlClient: SqlClient) {
        sqlClient
            .query(createTableQuery(table))
            .execute()
            .coAwait()
    }

    override suspend fun get(owner: String, layoutKey: String, sqlClient: SqlClient): MailLayout? {
        val query = "SELECT $SELECT_COLUMNS FROM `$table` WHERE `owner` = ? AND `layout_key` = ?"

        val rows = sqlClient
            .preparedQuery(query)
            .execute(Tuple.of(owner, layoutKey))
            .coAwait()

        return rows.firstOrNull()?.toEntity()
    }

    override suspend fun getAll(sqlClient: SqlClient): List<MailLayout> {
        val rows = sqlClient
            .preparedQuery("SELECT $SELECT_COLUMNS FROM `$table`")
            .execute()
            .coAwait()

        return rows.toEntities()
    }

    override suspend fun upsert(mailLayout: MailLayout, sqlClient: SqlClient) {
        val query = "INSERT INTO `$table` (`owner`, `layout_key`, `name`, `body`, `updated_at`) VALUES (?, ?, ?, ?, ?) " +
                "ON DUPLICATE KEY UPDATE `name` = VALUES(`name`), `body` = VALUES(`body`), `updated_at` = VALUES(`updated_at`)"

        sqlClient
            .preparedQuery(query)
            .execute(Tuple.of(mailLayout.owner, mailLayout.layoutKey, mailLayout.name, mailLayout.body, mailLayout.updatedAt))
            .coAwait()
    }

    override suspend fun delete(owner: String, layoutKey: String, sqlClient: SqlClient): Boolean {
        val rows = sqlClient
            .preparedQuery("DELETE FROM `$table` WHERE `owner` = ? AND `layout_key` = ?")
            .execute(Tuple.of(owner, layoutKey))
            .coAwait()

        return rows.rowCount() > 0
    }

    companion object {
        private const val SELECT_COLUMNS = "`id`, `owner`, `layout_key` AS `layoutKey`, `name`, `body`, `updated_at` AS `updatedAt`"

        /** Shared with `DatabaseMigration62to63`, so a fresh install and an upgrade get one table. */
        fun createTableQuery(table: String) = """
            CREATE TABLE IF NOT EXISTS `$table` (
              `id` bigint NOT NULL AUTO_INCREMENT,
              `owner` varchar(255) NOT NULL,
              `layout_key` varchar(128) NOT NULL,
              `name` varchar(255) NOT NULL DEFAULT '',
              `body` mediumtext NOT NULL,
              `updated_at` bigint NOT NULL,
              PRIMARY KEY (`id`),
              UNIQUE KEY `idx_mail_layout` (`owner`, `layout_key`)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Overrides of bundled mail layouts and admin-made layouts.';
        """.trimIndent()
    }
}
