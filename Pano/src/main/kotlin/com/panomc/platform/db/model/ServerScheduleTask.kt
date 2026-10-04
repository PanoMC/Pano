package com.panomc.platform.db.model

import com.panomc.platform.db.DBEntity
import com.panomc.platform.server.schedule.ScheduleTaskKind
import io.vertx.core.json.JsonObject

/**
 * One step of a [ServerSchedule], in the order it runs.
 *
 * [payload] is raw JSON rather than a typed column set because each kind carries something
 * different — `{ "action": "RESTART" }`, `{ "command": "save-all" }`, `{ "name": "nightly" }` —
 * and three mostly-null columns would be a worse way to say the same thing. It is validated on the
 * way in, so what comes out is always the shape its kind promises.
 */
data class ServerScheduleTask(
    val id: Long = -1,
    val scheduleId: Long,
    /** Zero-based; the order tasks run in and the order the panel shows them. */
    var position: Int,
    var kind: ScheduleTaskKind,
    var payload: String = "{}"
) : DBEntity() {
    fun payloadObject(): JsonObject = try {
        JsonObject(payload)
    } catch (_: Exception) {
        JsonObject()
    }

    fun toPublicJsonObject(): JsonObject = JsonObject()
        .put("id", id)
        .put("position", position)
        .put("kind", kind.name)
        .put("payload", payloadObject())

    override fun hashCode(): Int = id.hashCode()

    override fun equals(other: Any?): Boolean = other is ServerScheduleTask && other.id == this.id
}
