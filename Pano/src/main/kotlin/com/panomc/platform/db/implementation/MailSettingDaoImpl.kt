package com.panomc.platform.db.implementation

import com.panomc.platform.annotation.Dao
import com.panomc.platform.db.dao.MailSettingDao
import com.panomc.platform.db.model.MailSetting
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.sqlclient.SqlClient
import io.vertx.sqlclient.Tuple

@Dao
class MailSettingDaoImpl : MailSettingDao() {
    private val table get() = getTablePrefix() + tableName

    override suspend fun init(sqlClient: SqlClient) {
        sqlClient
            .query(createTableQuery(table))
            .execute()
            .coAwait()
    }

    override suspend fun get(owner: String, mailKey: String, sqlClient: SqlClient): MailSetting? {
        val rows = sqlClient
            .preparedQuery("SELECT $SELECT_COLUMNS FROM `$table` WHERE `owner` = ? AND `mail_key` = ?")
            .execute(Tuple.of(owner, mailKey))
            .coAwait()

        return rows.firstOrNull()?.toEntity()
    }

    override suspend fun getAll(sqlClient: SqlClient): List<MailSetting> {
        val rows = sqlClient
            .preparedQuery("SELECT $SELECT_COLUMNS FROM `$table`")
            .execute()
            .coAwait()

        return rows.toEntities()
    }

    override suspend fun upsert(mailSetting: MailSetting, sqlClient: SqlClient) {
        val query = "INSERT INTO `$table` (`owner`, `mail_key`, `layout`, `updated_at`) VALUES (?, ?, ?, ?) " +
                "ON DUPLICATE KEY UPDATE `layout` = VALUES(`layout`), `updated_at` = VALUES(`updated_at`)"

        sqlClient
            .preparedQuery(query)
            .execute(Tuple.of(mailSetting.owner, mailSetting.mailKey, mailSetting.layout, mailSetting.updatedAt))
            .coAwait()
    }

    override suspend fun delete(owner: String, mailKey: String, sqlClient: SqlClient): Boolean {
        val rows = sqlClient
            .preparedQuery("DELETE FROM `$table` WHERE `owner` = ? AND `mail_key` = ?")
            .execute(Tuple.of(owner, mailKey))
            .coAwait()

        return rows.rowCount() > 0
    }

    override suspend fun replaceLayout(fromLayout: String, toLayout: String, sqlClient: SqlClient): List<MailSetting> {
        val moved = sqlClient
            .preparedQuery("SELECT $SELECT_COLUMNS FROM `$table` WHERE `layout` = ?")
            .execute(Tuple.of(fromLayout))
            .coAwait()
            .toEntities()

        if (moved.isEmpty()) return moved

        sqlClient
            .preparedQuery("UPDATE `$table` SET `layout` = ?, `updated_at` = ? WHERE `layout` = ?")
            .execute(Tuple.of(toLayout, System.currentTimeMillis(), fromLayout))
            .coAwait()

        return moved
    }

    companion object {
        private const val SELECT_COLUMNS = "`id`, `owner`, `mail_key` AS `mailKey`, `layout`, `updated_at` AS `updatedAt`"

        /** Shared with `DatabaseMigration62to63`, so a fresh install and an upgrade get one table. */
        fun createTableQuery(table: String) = """
            CREATE TABLE IF NOT EXISTS `$table` (
              `id` bigint NOT NULL AUTO_INCREMENT,
              `owner` varchar(255) NOT NULL,
              `mail_key` varchar(128) NOT NULL,
              `layout` varchar(255) NOT NULL,
              `updated_at` bigint NOT NULL,
              PRIMARY KEY (`id`),
              UNIQUE KEY `idx_mail_setting` (`owner`, `mail_key`)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Per-mail settings chosen in the panel (the layout).';
        """.trimIndent()
    }
}
