package com.panomc.platform.server

import com.panomc.platform.util.TextUtil.convertToSnakeCase
import java.util.*

interface ServerEventResponse : PlatformMessage {
    val eventId: UUID

    override fun getResponseName() =
        this.javaClass.simpleName.replace("ServerEventResponse", "").convertToSnakeCase().uppercase()
}