package com.panomc.platform.db.dao

import com.panomc.platform.db.Dao
import com.panomc.platform.db.model.MailTemplate
import io.vertx.sqlclient.SqlClient

abstract class MailTemplateDao : Dao<MailTemplate>(MailTemplate::class.java) {
    abstract suspend fun get(owner: String, mailKey: String, variant: String, localeCode: String, sqlClient: SqlClient): MailTemplate?

    /** Every override without its texts: what the panel's list needs to mark customised languages. */
    abstract suspend fun getAllKeys(sqlClient: SqlClient): List<MailTemplateKey>

    /** Inserts or replaces the row of the same owner / key / variant / locale. */
    abstract suspend fun upsert(mailTemplate: MailTemplate, sqlClient: SqlClient)

    /** Returns whether a row was deleted. */
    abstract suspend fun delete(owner: String, mailKey: String, variant: String, localeCode: String, sqlClient: SqlClient): Boolean

    data class MailTemplateKey(val owner: String, val mailKey: String, val variant: String, val localeCode: String)
}
