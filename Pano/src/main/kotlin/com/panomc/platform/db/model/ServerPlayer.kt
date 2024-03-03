package com.panomc.platform.db.model

import com.panomc.platform.db.DBEntity
import java.util.*

data class ServerPlayer(
    val id: Long = -1,
    val uuid: UUID,
    val username: String,
    val ping: Long = 0,
    val serverId: Long,
    val loginTime: Long
) : DBEntity()