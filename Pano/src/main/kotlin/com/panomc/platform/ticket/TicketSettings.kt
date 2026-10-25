package com.panomc.platform.ticket

import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.SystemProperty
import io.vertx.sqlclient.SqlClient

/**
 * What an admin decides about tickets as a whole (panel: Tickets, the gear). Kept in `system_property`, like the
 * other things the panel sets that are not server configuration.
 */
object TicketSettings {
    /** `"false"` when a ticket must be opened in a category; anything else, or no row, allows a ticket without one. */
    const val ALLOW_NO_CATEGORY = "ticket_allow_no_category"

    /** The admin's choice: may a ticket be opened without a category. On unless turned off. */
    suspend fun allowNoCategory(databaseManager: DatabaseManager, sqlClient: SqlClient): Boolean =
        databaseManager.systemPropertyDao.getByOption(ALLOW_NO_CATEGORY, sqlClient)?.value != "false"

    /**
     * Whether a new ticket may go without a category right now: the admin allows it, or there is no category that
     * is on, in which case requiring one would close the support desk altogether.
     */
    suspend fun noCategoryPossible(databaseManager: DatabaseManager, sqlClient: SqlClient): Boolean =
        allowNoCategory(databaseManager, sqlClient) || databaseManager.ticketCategoryDao.countEnabled(sqlClient) == 0L

    suspend fun setAllowNoCategory(allow: Boolean, databaseManager: DatabaseManager, sqlClient: SqlClient) =
        set(ALLOW_NO_CATEGORY, allow.toString(), databaseManager, sqlClient)

    /**
     * The template sent by itself as the first answer of every new ticket whose category has no auto-reply of its own
     * (and of a ticket without a category); none when unset.
     */
    const val DEFAULT_AUTO_REPLY_TEMPLATE = "ticket_default_auto_reply_template"

    /** The default auto-reply template of new tickets, -1 for none. */
    suspend fun defaultAutoReplyTemplateId(databaseManager: DatabaseManager, sqlClient: SqlClient): Long =
        databaseManager.systemPropertyDao.getByOption(DEFAULT_AUTO_REPLY_TEMPLATE, sqlClient)?.value?.toLongOrNull()
            ?: -1L

    suspend fun setDefaultAutoReplyTemplateId(templateId: Long, databaseManager: DatabaseManager, sqlClient: SqlClient) =
        set(DEFAULT_AUTO_REPLY_TEMPLATE, templateId.toString(), databaseManager, sqlClient)

    /**
     * The template sent by itself to a new ticket opened without a category, before the default one; none when unset.
     * Only used while tickets without a category are allowed.
     */
    const val NO_CATEGORY_AUTO_REPLY_TEMPLATE = "ticket_no_category_auto_reply_template"

    /** The auto-reply template of tickets without a category, -1 for none (the default one is sent then). */
    suspend fun noCategoryAutoReplyTemplateId(databaseManager: DatabaseManager, sqlClient: SqlClient): Long =
        databaseManager.systemPropertyDao.getByOption(NO_CATEGORY_AUTO_REPLY_TEMPLATE, sqlClient)?.value?.toLongOrNull()
            ?: -1L

    suspend fun setNoCategoryAutoReplyTemplateId(templateId: Long, databaseManager: DatabaseManager, sqlClient: SqlClient) =
        set(NO_CATEGORY_AUTO_REPLY_TEMPLATE, templateId.toString(), databaseManager, sqlClient)

    private suspend fun set(option: String, value: String, databaseManager: DatabaseManager, sqlClient: SqlClient) {
        val property = SystemProperty(option = option, value = value)

        if (databaseManager.systemPropertyDao.existsByOption(property.option, sqlClient)) {
            databaseManager.systemPropertyDao.update(property.option, property.value, sqlClient)
        } else {
            databaseManager.systemPropertyDao.add(property, sqlClient)
        }
    }
}
