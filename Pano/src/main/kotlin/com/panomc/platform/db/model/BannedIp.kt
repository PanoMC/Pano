package com.panomc.platform.db.model

import com.panomc.platform.db.DBEntity

open class BannedIp(
    val id: Long = -1,
    val ip: String,
    val reason: String? = null,
    val bannedUntil: Long? = null,
    val bannedBy: String? = null,
    val source: String? = null,
    val bannedBySystem: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
) : DBEntity()
