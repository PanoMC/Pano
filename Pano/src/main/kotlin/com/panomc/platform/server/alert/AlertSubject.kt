package com.panomc.platform.server.alert

import com.panomc.platform.db.model.ServerAlert

/**
 * What an alert's cooldown subject means in `server_alert` columns (SM-69, §2.4.34).
 *
 * The cooldown is keyed by a subject string (`server:<id>`, `node:<id>`, `server:<id>:<schedule>`)
 * while a stored row only has `serverId` and `nodeId`. This is the bridge between the two, used to
 * seed the in-memory cooldown from the table after a restart -- without it every boot forgot that
 * a daily alert had already been said today and said it again fifteen minutes later.
 */
data class AlertSubject(val serverId: Long?, val nodeId: Long?) {
    companion object {
        /**
         * The columns a subject is stored under, or null when a row cannot tell this subject apart
         * from another one.
         *
         * A schedule's subject names the schedule, which only the row's message mentions; looking
         * it up by server would let one failing schedule silence a different one, so those stay on
         * the in-memory cooldown alone, as before.
         */
        fun parse(subject: String): AlertSubject? {
            val parts = subject.split(':')

            if (parts.size != 2) {
                return null
            }

            val id = parts[1].toLongOrNull() ?: return null

            return when (parts[0]) {
                "server" -> AlertSubject(serverId = id, nodeId = null)
                "node" -> AlertSubject(serverId = null, nodeId = id)
                else -> null
            }
        }

        /**
         * When the stored [row] says this alert was last raised, or null when it should not count.
         *
         * A resolved row is a condition that went away (a node that came back), which in-process
         * is a cooldown cleared on the spot; the next occurrence is news and must not be held back
         * by the one before it.
         */
        fun lastRaiseOf(row: ServerAlert?): Long? {
            if (row == null || row.resolvedAt != null) {
                return null
            }

            return row.createdAt
        }
    }
}
