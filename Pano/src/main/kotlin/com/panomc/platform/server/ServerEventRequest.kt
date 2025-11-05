package com.panomc.platform.server

import java.util.UUID

abstract class ServerEventRequest() {
    val eventId: UUID? = null
}