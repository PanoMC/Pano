package com.panomc.platform.db.dao

import com.panomc.platform.db.Dao
import com.panomc.platform.db.model.TicketCategoryFieldSet
import com.panomc.platform.db.model.TicketFieldSet
import io.vertx.sqlclient.SqlClient

/** The custom field definitions of ticket categories. */
abstract class TicketCategoryFieldSetDao : Dao<TicketCategoryFieldSet>(TicketCategoryFieldSet::class.java) {
    /** The definitions JSON of [categoryId], or `null` when the category has no fields. */
    abstract suspend fun getByCategoryId(categoryId: Long, sqlClient: SqlClient): String?

    /** The definitions JSON of every listed category that has fields. */
    abstract suspend fun getByCategoryIds(categoryIds: Collection<Long>, sqlClient: SqlClient): Map<Long, String>

    /** Stores [fields] for [categoryId]; an empty array removes the row. */
    abstract suspend fun set(categoryId: Long, fields: String, sqlClient: SqlClient)

    abstract suspend fun deleteByCategoryId(categoryId: Long, sqlClient: SqlClient)
}

/** The field value snapshots of tickets. */
abstract class TicketFieldSetDao : Dao<TicketFieldSet>(TicketFieldSet::class.java) {
    /** The snapshot JSON of [ticketId], or `null` when it has none. */
    abstract suspend fun getByTicketId(ticketId: Long, sqlClient: SqlClient): String?

    /** Stores the snapshot [fields] and [searchText] (its readable values, what the panel search looks in). */
    abstract suspend fun set(ticketId: Long, fields: String, searchText: String, sqlClient: SqlClient)

    abstract suspend fun deleteByTicketIds(ticketIds: Collection<Long>, sqlClient: SqlClient)

    /** The table name with its prefix, for the panel search's sub-query. */
    fun qualifiedTable(): String = "`${getTablePrefix() + tableName}`"
}
