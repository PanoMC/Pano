package com.panomc.platform.auth.panel.log

import com.panomc.platform.db.model.PanelActivityLog
import io.vertx.core.json.JsonObject

/** `EDITED_TICKET_FIELDS`: a staff member changed the custom field values of a ticket. */
class EditedTicketFieldsLog(
    userId: Long,
    username: String,
    ticketId: Long,
) : PanelActivityLog(
    userId = userId,
    details = JsonObject().put("username", username).put("ticketId", ticketId)
)
