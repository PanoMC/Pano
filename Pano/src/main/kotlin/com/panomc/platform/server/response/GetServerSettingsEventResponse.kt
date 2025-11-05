package com.panomc.platform.server.response

import com.panomc.platform.server.ServerEventResponse

data class GetServerSettingsEventResponse(
    val authIntegration: Boolean,
    val banIntegration: Boolean,
    val permissionIntegration: Boolean,
    val translations: Map<String, Map<String, String>>,
    val locale: String,
) : ServerEventResponse()