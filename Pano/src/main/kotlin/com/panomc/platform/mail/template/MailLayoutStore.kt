package com.panomc.platform.mail.template

import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.MailLayout
import com.panomc.platform.db.model.MailSetting
import io.vertx.sqlclient.SqlClient
import org.springframework.beans.factory.ObjectProvider

/** What [MailTemplateService] reads about layouts: layout rows (overrides and custom layouts) and each mail's choice. */
interface MailLayoutStore {
    suspend fun layout(sqlClient: SqlClient?, owner: String, key: String): MailLayout?

    suspend fun layouts(sqlClient: SqlClient?): List<MailLayout>

    /** The admin's layout choice for the mail, null when it has none. */
    suspend fun mailSetting(sqlClient: SqlClient?, owner: String, mailKey: String): String?

    suspend fun mailSettings(sqlClient: SqlClient?): List<MailSetting>

    companion object {
        /** No database: only bundled layouts and the mails' own defaults. */
        val EMPTY: MailLayoutStore = object : MailLayoutStore {
            override suspend fun layout(sqlClient: SqlClient?, owner: String, key: String): MailLayout? = null
            override suspend fun layouts(sqlClient: SqlClient?): List<MailLayout> = emptyList()
            override suspend fun mailSetting(sqlClient: SqlClient?, owner: String, mailKey: String): String? = null
            override suspend fun mailSettings(sqlClient: SqlClient?): List<MailSetting> = emptyList()
        }

        /** Reads `mail_layout` / `mail_setting`; without an SQL client it answers like [EMPTY]. */
        fun database(databaseManager: ObjectProvider<DatabaseManager>): MailLayoutStore = object : MailLayoutStore {
            override suspend fun layout(sqlClient: SqlClient?, owner: String, key: String): MailLayout? =
                sqlClient?.let { databaseManager.getObject().mailLayoutDao.get(owner, key, it) }

            override suspend fun layouts(sqlClient: SqlClient?): List<MailLayout> =
                sqlClient?.let { databaseManager.getObject().mailLayoutDao.getAll(it) }.orEmpty()

            override suspend fun mailSetting(sqlClient: SqlClient?, owner: String, mailKey: String): String? =
                sqlClient?.let { databaseManager.getObject().mailSettingDao.get(owner, mailKey, it)?.layout }

            override suspend fun mailSettings(sqlClient: SqlClient?): List<MailSetting> =
                sqlClient?.let { databaseManager.getObject().mailSettingDao.getAll(it) }.orEmpty()
        }
    }
}
