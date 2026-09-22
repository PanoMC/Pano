package com.panomc.platform.auth.panel.log

import com.panomc.platform.db.model.PanelActivityLog
import io.vertx.core.json.JsonObject

/**
 * Records that [username] created, restored, downloaded, deleted, pinned or unpinned a backup.
 *
 * A restore replaces a live server's world and a delete destroys the only copy of one, so both are
 * audited next to the other destructive panel actions rather than treated as file operations.
 */
class ServerBackupActionLog(
    userId: Long,
    username: String,
    serverId: Long,
    action: String,
    backupId: String,
    name: String? = null
) : PanelActivityLog(
    userId = userId,
    details = JsonObject()
        .put("username", username)
        .put("serverId", serverId)
        .put("action", action)
        .put("backupId", backupId)
        .put("name", name)
) {
    companion object {
        const val ACTION_CREATE = "CREATE"
        const val ACTION_RESTORE = "RESTORE"
        const val ACTION_DELETE = "DELETE"
        const val ACTION_DOWNLOAD = "DOWNLOAD"
        const val ACTION_RETENTION = "RETENTION"

        /** Taken out of retention's reach; audited because it decides what survives a prune. */
        const val ACTION_PIN = "PIN"
        const val ACTION_UNPIN = "UNPIN"
    }
}
