package com.panomc.platform.db.model

import com.panomc.platform.db.DBEntity
import com.panomc.platform.node.ServerTaskKind
import com.panomc.platform.node.ServerTaskStatus
import io.vertx.core.json.JsonObject

/**
 * One long-running job a node is doing, such as installing or deleting a server.
 *
 * Rows are the durable half of the `TASK_PROGRESS` stream: the live frames go straight to the
 * panel over the hub, and this is what the panel reads when it opens the page after the fact, or
 * when Pano restarted while the node kept working.
 *
 * [serverId] is nullable because a task can outlive its server (a delete finishes after the row is
 * gone) or never have one (a node self-update). [nodeId] is nullable for the same kind of reason:
 * a node bootstrap is the work of creating the node itself.
 */
data class ServerTask(
    val id: Long = -1,
    /** Id shared with the node; the node only ever refers to a task by this. */
    val uuid: String,
    var serverId: Long? = null,
    /**
     * Node doing the work, or null while there is none: a node bootstrap installs the daemon that
     * would have been the answer.
     */
    val nodeId: Long? = null,
    val kind: ServerTaskKind,
    var status: ServerTaskStatus = ServerTaskStatus.PENDING,
    var percent: Int = 0,
    var message: String? = null,
    var error: String? = null,
    /** User who started it, so progress can be pushed back to exactly that panel session. */
    val createdBy: Long,
    val createdAt: Long = System.currentTimeMillis(),
    var updatedAt: Long = System.currentTimeMillis()
) : DBEntity() {
    fun toPublicJsonObject(): JsonObject = JsonObject.mapFrom(this)

    override fun hashCode(): Int = id.hashCode()

    override fun equals(other: Any?): Boolean = other is ServerTask && other.id == this.id
}
