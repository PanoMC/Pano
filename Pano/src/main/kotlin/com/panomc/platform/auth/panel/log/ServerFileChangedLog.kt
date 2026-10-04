package com.panomc.platform.auth.panel.log

import com.panomc.platform.db.model.PanelActivityLog
import io.vertx.core.json.JsonObject

/**
 * Records that [username] changed something in a managed server's files.
 *
 * The path is recorded and the content never is. A `server.properties` or a permissions file can
 * hold anything an operator put in it, and an audit log that quotes the file would end up holding
 * a copy of every secret anyone has ever edited through the panel — while answering the only
 * question an audit log is asked, which is who touched what and when, no better than the path
 * does.
 */
class ServerFileChangedLog(
    userId: Long,
    username: String,
    serverId: Long,
    action: String,
    path: String,
    /** Second path for the operations that have one, such as a rename's destination. */
    target: String? = null
) : PanelActivityLog(
    userId = userId,
    details = JsonObject()
        .put("username", username)
        .put("serverId", serverId)
        .put("action", action)
        .put("path", path)
        .put("target", target)
) {
    companion object {
        const val ACTION_WRITE = "WRITE"
        const val ACTION_MKDIR = "MKDIR"
        const val ACTION_DELETE = "DELETE"
        const val ACTION_RENAME = "RENAME"
        const val ACTION_ARCHIVE = "ARCHIVE"
        const val ACTION_UNARCHIVE = "UNARCHIVE"
        const val ACTION_CHMOD = "CHMOD"
        const val ACTION_UPLOAD = "UPLOAD"
        const val ACTION_DOWNLOAD = "DOWNLOAD"
    }
}
