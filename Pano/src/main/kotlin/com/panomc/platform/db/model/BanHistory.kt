package com.panomc.platform.db.model

import com.panomc.platform.db.DBEntity

open class BanHistory(
    val id: Long = -1,
    val userId: Long,
    val reason: String? = null,
    val emailNotified: Boolean? = false,
    val bannedUntil: Long? = null,
    val bannedBy: String? = null,
    val bannedBySystem: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
) : DBEntity()