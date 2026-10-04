package com.panomc.platform.node

import com.panomc.platform.util.TextUtil.convertToSnakeCase
import java.util.UUID

/** A reply to a node request, carrying the request's [eventId] back to the node. */
abstract class NodeEventResponse : NodeMessage {
    var eventId: UUID? = null

    override fun getResponseName() =
        this.javaClass.simpleName.replace("EventResponse", "").convertToSnakeCase().uppercase()
}
