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

    suspend fun setAllowNoCategory(allow: Boolean, databaseManager: DatabaseManager, sqlClient: SqlClient) {
        val property = SystemProperty(option = ALLOW_NO_CATEGORY, value = allow.toString())

        if (databaseManager.systemPropertyDao.existsByOption(property.option, sqlClient)) {
            databaseManager.systemPropertyDao.update(property.option, property.value, sqlClient)
        } else {
            databaseManager.systemPropertyDao.add(property, sqlClient)
        }
    }
}
