package com.panomc.platform.node.message

import com.panomc.platform.node.NodeMessage

/**
 * Hands a node the complete set of schedules for one server (`SYNC_SCHEDULES`).
 *
 * Always the full set, never a delta. A schedule that fires at four in the morning has to survive
 * a Pano restart, a node reconnect and an edit made while the node was offline, and reconciling
 * three kinds of incremental update across that is how a server ends up being restarted twice or
 * not at all. Replacing the list outright makes the node's copy a function of Pano's, with no
 * state in between to go wrong.
 *
 * An empty [schedules] is therefore meaningful: it means this server has none, and the node is to
 * forget whatever it was holding.
 */
data class SyncSchedulesMessage(
    val serverUuid: String,
    val schedules: List<SyncScheduleEntry>
) : NodeMessage

data class SyncScheduleEntry(
    val uuid: String,
    val name: String,
    val cron: String,
    val timezone: String,
    val enabled: Boolean,
    val warnMinutes: Int,
    val tasks: List<SyncScheduleTaskEntry>
)

data class SyncScheduleTaskEntry(
    val kind: String,
    /** `{ "action" }`, `{ "command" }` or `{ "name" }`, already validated by Pano. */
    val payload: Map<String, Any?>
)
