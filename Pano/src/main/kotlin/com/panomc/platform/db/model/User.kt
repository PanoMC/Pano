package com.panomc.platform.db.model

import com.panomc.platform.db.DBEntity

data class User(
    val id: Long = -1,
    val username: String,
    val email: String? = null,
    val registeredIp: String,
    val registerDate: Long = System.currentTimeMillis(),
    val lastLoginDate: Long = System.currentTimeMillis(),
    val emailVerified: Boolean = false,
    val banned: Boolean = false,
    val banMessage: String? = null,
    val bannedUntil: Long? = null,
    val canCreateTicket: Boolean = true,
    val lastActivityTime: Long = 0L,
    val lastPanelActivityTime: Long = 0L,
    val localeCode: String? = null,
) : DBEntity()