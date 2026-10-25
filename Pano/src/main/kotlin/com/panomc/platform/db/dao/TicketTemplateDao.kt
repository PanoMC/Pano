package com.panomc.platform.db.dao

import com.panomc.platform.db.Dao
import com.panomc.platform.db.model.TicketTemplate
import io.vertx.sqlclient.SqlClient

abstract class TicketTemplateDao : Dao<TicketTemplate>(TicketTemplate::class.java) {
    /** How many templates there are; with [search], those whose title or text has it. */
    abstract suspend fun count(search: String?, sqlClient: SqlClient): Long

    /** One page of the templates by title: [limit] rows after skipping [offset]; [search] as in [count]. */
    abstract suspend fun getList(search: String?, limit: Int, offset: Long, sqlClient: SqlClient): List<TicketTemplate>

    abstract suspend fun getById(id: Long, sqlClient: SqlClient): TicketTemplate?

    abstract suspend fun add(template: TicketTemplate, sqlClient: SqlClient): Long

    abstract suspend fun update(template: TicketTemplate, sqlClient: SqlClient)

    abstract suspend fun deleteById(id: Long, sqlClient: SqlClient)
}
