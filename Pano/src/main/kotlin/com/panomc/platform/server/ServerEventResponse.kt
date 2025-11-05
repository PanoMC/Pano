package com.panomc.platform.server

import com.panomc.platform.util.TextUtil.convertToSnakeCase
import java.util.*

abstract class ServerEventResponse : PlatformMessage {
    var eventId: UUID? = null

    override fun getResponseName() =
        this.javaClass.simpleName.replace("EventResponse", "").convertToSnakeCase().uppercase()
}