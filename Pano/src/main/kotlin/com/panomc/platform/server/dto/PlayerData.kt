package com.panomc.platform.server.dto

import java.util.*

data class PlayerData(
    val uuid: UUID,
    val username: String,
    val ping: Long,
    val loginTime: Long,
    val ipAddress: String
)