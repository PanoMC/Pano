package com.panomc.platform.db.model

import com.panomc.platform.db.DBEntity
import com.panomc.platform.server.alert.ServerAlertKind
import io.vertx.core.json.JsonObject

/**
 * One thing that went wrong, recorded so it can still be looked at after the notification is gone.
 *
 * Notifications are read and dismissed; this table is what answers "how often has this node gone
 * offline this month". Either [serverId] or [nodeId] is set, and sometimes both — a managed
 * server's crash belongs to its node as much as to itself.
 */
data class ServerAlert(
    val id: Long = -1,
    val kind: ServerAlertKind,
    val serverId: Long? = null,
    val nodeId: Long? = null,
    var message: String,
    val createdAt: Long = System.currentTimeMillis(),
    var resolvedAt: Long? = null
) : DBEntity() {
    fun toPublicJsonObject(): JsonObject = JsonObject.mapFrom(this)

    override fun hashCode(): Int = id.hashCode()

    override fun equals(other: Any?): Boolean = other is ServerAlert && other.id == this.id
}
