package com.panomc.platform.node.event.request

import com.panomc.platform.node.NodeEventRequest

/** How one scheduled run went on the node. */
data class ScheduleRunEventRequest(
    val serverUuid: String? = null,
    val scheduleUuid: String? = null,
    val startedAt: Long? = null,
    val finishedAt: Long? = null,
    val ok: Boolean? = null,
    val error: String? = null
) : NodeEventRequest()
