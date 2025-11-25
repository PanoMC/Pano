package com.panomc.platform.server.message

import com.panomc.platform.server.PlatformMessage

data class BanPlayerMessage(
    val username: String,
    val locale: String?,
    val banReason: String? = null,
    val bannedUntil: Long? = null,
) : PlatformMessage