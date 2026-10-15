package com.panomc.platform.db.dao

import com.panomc.platform.db.Dao
import com.panomc.platform.db.model.MailSetting
import io.vertx.sqlclient.SqlClient

abstract class MailSettingDao : Dao<MailSetting>(MailSetting::class.java) {
    abstract suspend fun get(owner: String, mailKey: String, sqlClient: SqlClient): MailSetting?

    abstract suspend fun getAll(sqlClient: SqlClient): List<MailSetting>

    /** Inserts or replaces the row of the same owner / mail key. */
    abstract suspend fun upsert(mailSetting: MailSetting, sqlClient: SqlClient)

    /** Returns whether a row was deleted. */
    abstract suspend fun delete(owner: String, mailKey: String, sqlClient: SqlClient): Boolean

    /** Points every mail that uses [fromLayout] at [toLayout]; returns the mails moved. */
    abstract suspend fun replaceLayout(fromLayout: String, toLayout: String, sqlClient: SqlClient): List<MailSetting>
}
