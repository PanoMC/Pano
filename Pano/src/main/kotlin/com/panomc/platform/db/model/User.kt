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
    // A new user starts at its last login, never at 0 (which shows as 1 January 1970).
    val lastActivityTime: Long = lastLoginDate,
    val lastPanelActivityTime: Long = 0L,
    val localeCode: String? = null,
    val linkCode: String? = null,
    val linkCodeCreatedAt: Long? = null,
    val mcUuid: String? = null,
) : DBEntity()