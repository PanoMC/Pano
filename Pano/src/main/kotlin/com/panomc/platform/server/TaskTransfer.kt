package com.panomc.platform.server

import io.vertx.core.json.JsonObject

/**
 * What a running task is downloading, as its latest frame said: bytes so far, the size when the
 * upstream gave one, and the rate. The panel writes it next to the percentage ("120 MB / 1 GB,
 * 12 MB/s").
 *
 * Never stored: a figure about the last second is worth nothing after a restart, and the next
 * frame brings a new one. A frame of a step that is not downloading carries none, which is what
 * takes the line away again.
 */
data class TaskTransfer(val done: Long, val total: Long?, val bytesPerSecond: Long?) {
    fun toJsonObject(): JsonObject = JsonObject()
        .put("done", done)
        .put("total", total)
        .put("bytesPerSecond", bytesPerSecond)

    companion object {
        /** The transfer on a frame, or null when it has none or its numbers make no sense. */
        fun of(done: Long?, total: Long?, bytesPerSecond: Long?): TaskTransfer? {
            if (done == null || done < 0) {
                return null
            }

            return TaskTransfer(
                done = done,
                total = total?.takeIf { it > 0 },
                bytesPerSecond = bytesPerSecond?.takeIf { it >= 0 }
            )
        }
    }
}
