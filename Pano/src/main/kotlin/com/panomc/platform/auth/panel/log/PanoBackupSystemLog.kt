package com.panomc.platform.auth.panel.log

import com.panomc.platform.db.model.PanelActivityLog
import io.vertx.core.json.JsonObject

/**
 * Records what Pano did with its own backups on a schedule, with nobody pressing anything: the
 * scheduled local backup, the scheduled upload to Pano Backup, and the old backups retention
 * removed ([count] of them).
 */
class PanoBackupSystemLog(
    action: String,
    ok: Boolean = true,
    count: Int = 0
) : PanelActivityLog(
    details = JsonObject()
        .put("action", action)
        .put("ok", ok)
        .put("count", count)
) {
    companion object {
        const val ACTION_SCHEDULED = "SCHEDULED"
        const val ACTION_SCHEDULED_UPLOAD = "SCHEDULED_UPLOAD"
        const val ACTION_RETENTION = "RETENTION"
    }
}
