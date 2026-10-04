package com.panomc.platform.auth.panel.log

import com.panomc.platform.db.model.PanelActivityLog
import io.vertx.core.json.JsonObject

/**
 * Records a server backup nobody pressed a button for: one a schedule took, and one retention
 * removed to keep the server's (or the schedule's) keep-last.
 *
 * [ServerBackupActionLog] covers the manual actions; without this the audit trail showed a
 * backup appearing or disappearing with no entry at all.
 */
class ServerBackupSystemLog(
    serverId: Long,
    action: String,
    backupId: String,
    name: String?
) : PanelActivityLog(
    details = JsonObject()
        .put("serverId", serverId)
        .put("action", action)
        .put("backupId", backupId)
        .put("name", name ?: backupId)
) {
    companion object {
        const val ACTION_SCHEDULED = "SCHEDULED"
        const val ACTION_RETENTION = "RETENTION"
    }
}
