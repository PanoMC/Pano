package com.panomc.platform.db.dao

import com.panomc.platform.db.Dao
import com.panomc.platform.db.model.MailLayout
import io.vertx.sqlclient.SqlClient

abstract class MailLayoutDao : Dao<MailLayout>(MailLayout::class.java) {
    abstract suspend fun get(owner: String, layoutKey: String, sqlClient: SqlClient): MailLayout?

    abstract suspend fun getAll(sqlClient: SqlClient): List<MailLayout>

    /** Inserts or replaces the row of the same owner / key. */
    abstract suspend fun upsert(mailLayout: MailLayout, sqlClient: SqlClient)

    /** Returns whether a row was deleted. */
    abstract suspend fun delete(owner: String, layoutKey: String, sqlClient: SqlClient): Boolean
}
