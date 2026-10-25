package com.panomc.platform.ticket

import com.github.jknack.handlebars.Handlebars
import com.panomc.platform.config.ConfigManager
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.Ticket
import io.vertx.sqlclient.SqlClient
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Fills the `{{variables}}` of a ticket template for one ticket. The template is the editor's HTML, so every value
 * is HTML-escaped; an unknown variable is left as it is written.
 */
object TicketTemplateRenderer {
    /** What a template may use, in the order the panel lists them. */
    val VARIABLES = listOf("username", "ticketId", "ticketTitle", "category", "staff", "website", "date")

    private val VARIABLE = Regex("""\{\{\s*([A-Za-z]+)\s*}}""")

    fun render(body: String, values: Map<String, String>): String =
        VARIABLE.replace(body) { match ->
            values[match.groupValues[1]]?.let { Handlebars.Utils.escapeExpression(it).toString() } ?: match.value
        }

    /**
     * The values for [ticket]. [staff] is the admin who answers; an auto-reply has none and is signed with the
     * website's name.
     */
    suspend fun values(
        ticket: Ticket,
        staff: String?,
        databaseManager: DatabaseManager,
        configManager: ConfigManager,
        sqlClient: SqlClient
    ): Map<String, String> {
        val website = configManager.config.websiteName
        val username = databaseManager.userDao.getUsernameFromUserId(ticket.userId, sqlClient) ?: "-"
        val category = if (ticket.categoryId == -1L) "-"
        else databaseManager.ticketCategoryDao.getById(ticket.categoryId, sqlClient)?.title ?: "-"

        return mapOf(
            "username" to username,
            "ticketId" to ticket.id.toString(),
            "ticketTitle" to ticket.title,
            "category" to category,
            "staff" to (staff ?: website),
            "website" to website,
            "date" to LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
        )
    }
}
