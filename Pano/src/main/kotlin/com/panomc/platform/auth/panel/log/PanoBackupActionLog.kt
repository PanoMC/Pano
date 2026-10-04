package com.panomc.platform.auth.panel.log

import com.panomc.platform.db.model.PanelActivityLog
import io.vertx.core.json.JsonObject

/**
 * Records what [username] did with the backups of the whole Pano (`/backups`): local backups, Pano
 * Backup, the encryption passphrase, the schedules and the transfer to Pano Host.
 *
 * Every one of these can replace, remove or carry the whole Pano (database, config, files) off the
 * server, so they are audited like the other destructive panel actions. The passphrase is only
 * ever recorded as "set", never its value.
 */
class PanoBackupActionLog(
    userId: Long,
    username: String,
    action: String,
    backupId: String? = null,
    /** The stable error code of an action that did not go through (a rejected or rolled back restore). */
    error: String? = null
) : PanelActivityLog(
    userId = userId,
    details = JsonObject()
        .put("username", username)
        .put("action", action)
        .put("backupId", backupId ?: "")
        .apply { error?.let { put("ok", false).put("error", it) } }
) {
    companion object {
        const val ACTION_CREATE = "CREATE"
        const val ACTION_DELETE = "DELETE"
        const val ACTION_DOWNLOAD = "DOWNLOAD"
        const val ACTION_RESTORE = "RESTORE"
        const val ACTION_RESTORE_FILE = "RESTORE_FILE"
        const val ACTION_SETTINGS = "SETTINGS"

        const val ACTION_UPLOAD = "UPLOAD"
        const val ACTION_DELETE_REMOTE = "DELETE_REMOTE"
        const val ACTION_RESTORE_REMOTE = "RESTORE_REMOTE"
        const val ACTION_REMOTE_SETTINGS = "REMOTE_SETTINGS"
        const val ACTION_PASSPHRASE = "PASSPHRASE"

        const val ACTION_TRANSFER = "TRANSFER"
        const val ACTION_TRANSFER_CANCEL = "TRANSFER_CANCEL"
    }
}
