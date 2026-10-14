package com.panomc.platform.db.implementation

import com.panomc.platform.annotation.Dao
import com.panomc.platform.db.dao.MailTemplateDao
import com.panomc.platform.db.model.MailTemplate
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.sqlclient.SqlClient
import io.vertx.sqlclient.Tuple

@Dao
class MailTemplateDaoImpl : MailTemplateDao() {
    private val table get() = getTablePrefix() + tableName

    override suspend fun init(sqlClient: SqlClient) {
        sqlClient
            .query(createTableQuery(table))
            .execute()
            .coAwait()
    }

    override suspend fun get(
        owner: String,
        mailKey: String,
        variant: String,
        localeCode: String,
        sqlClient: SqlClient
    ): MailTemplate? {
        val query = "SELECT $SELECT_COLUMNS FROM `$table` " +
                "WHERE `owner` = ? AND `mail_key` = ? AND `variant` = ? AND `locale_code` = ?"

        val rows = sqlClient
            .preparedQuery(query)
            .execute(Tuple.of(owner, mailKey, variant, localeCode))
            .coAwait()

        return rows.firstOrNull()?.toEntity()
    }

    override suspend fun getAllKeys(sqlClient: SqlClient): List<MailTemplateKey> {
        val query = "SELECT `owner`, `mail_key`, `variant`, `locale_code` FROM `$table`"

        val rows = sqlClient
            .preparedQuery(query)
            .execute()
            .coAwait()

        return rows.map {
            MailTemplateKey(
                it.getString("owner"),
                it.getString("mail_key"),
                it.getString("variant"),
                it.getString("locale_code")
            )
        }
    }

    override suspend fun upsert(mailTemplate: MailTemplate, sqlClient: SqlClient) {
        val query = "INSERT INTO `$table` " +
                "(`owner`, `mail_key`, `variant`, `locale_code`, `mode`, `subject`, `preheader`, `body`, " +
                "`button_label`, `button_url`, `footer`, `updated_at`) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) " +
                "ON DUPLICATE KEY UPDATE `mode` = VALUES(`mode`), `subject` = VALUES(`subject`), " +
                "`preheader` = VALUES(`preheader`), `body` = VALUES(`body`), `button_label` = VALUES(`button_label`), " +
                "`button_url` = VALUES(`button_url`), `footer` = VALUES(`footer`), `updated_at` = VALUES(`updated_at`)"

        sqlClient
            .preparedQuery(query)
            .execute(
                Tuple.wrap(
                    listOf(
                        mailTemplate.owner,
                        mailTemplate.mailKey,
                        mailTemplate.variant,
                        mailTemplate.localeCode,
                        mailTemplate.mode,
                        mailTemplate.subject,
                        mailTemplate.preheader,
                        mailTemplate.body,
                        mailTemplate.buttonLabel,
                        mailTemplate.buttonUrl,
                        mailTemplate.footer,
                        mailTemplate.updatedAt
                    )
                )
            )
            .coAwait()
    }

    override suspend fun delete(
        owner: String,
        mailKey: String,
        variant: String,
        localeCode: String,
        sqlClient: SqlClient
    ): Boolean {
        val query = "DELETE FROM `$table` WHERE `owner` = ? AND `mail_key` = ? AND `variant` = ? AND `locale_code` = ?"

        val rows = sqlClient
            .preparedQuery(query)
            .execute(Tuple.of(owner, mailKey, variant, localeCode))
            .coAwait()

        return rows.rowCount() > 0
    }

    companion object {
        private const val SELECT_COLUMNS = "`id`, `owner`, `mail_key` AS `mailKey`, `variant`, " +
                "`locale_code` AS `localeCode`, `mode`, `subject`, `preheader`, `body`, `button_label` AS `buttonLabel`, " +
                "`button_url` AS `buttonUrl`, `footer`, `updated_at` AS `updatedAt`"

        /** Shared with `DatabaseMigration61to62`, so a fresh install and an upgrade get one table. */
        fun createTableQuery(table: String) = """
            CREATE TABLE IF NOT EXISTS `$table` (
              `id` bigint NOT NULL AUTO_INCREMENT,
              `owner` varchar(255) NOT NULL,
              `mail_key` varchar(128) NOT NULL,
              `variant` varchar(128) NOT NULL,
              `locale_code` varchar(16) NOT NULL,
              `mode` varchar(16) NOT NULL DEFAULT 'FIELDS',
              `subject` text NOT NULL,
              `preheader` text NULL,
              `body` mediumtext NOT NULL,
              `button_label` text NULL,
              `button_url` text NULL,
              `footer` text NULL,
              `updated_at` bigint NOT NULL,
              PRIMARY KEY (`id`),
              UNIQUE KEY `idx_mail_template` (`owner`, `mail_key`, `variant`, `locale_code`)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Panel overrides of mail templates.';
        """.trimIndent()
    }
}
