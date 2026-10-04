package com.panomc.platform.auth.panel.log

import com.panomc.platform.db.model.PanelActivityLog
import io.vertx.core.json.JsonObject

/**
 * Records that [username] created, changed, enabled, disabled, deleted or manually ran a schedule.
 *
 * A schedule is code somebody wrote that runs unattended on a game server, so every change to one
 * is audited the same way a console command is.
 */
class ServerScheduleActionLog(
    userId: Long,
    username: String,
    serverId: Long,
    action: String,
    name: String,
    cron: String? = null
) : PanelActivityLog(
    userId = userId,
    details = JsonObject()
        .put("username", username)
        .put("serverId", serverId)
        .put("action", action)
        .put("name", name)
        .put("cron", cron)
) {
    companion object {
        const val ACTION_CREATE = "CREATE"
        const val ACTION_UPDATE = "UPDATE"
        const val ACTION_DELETE = "DELETE"
        const val ACTION_TOGGLE = "TOGGLE"
        const val ACTION_RUN = "RUN"
    }
}
