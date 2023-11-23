package com.panomc.platform.db.model

import com.panomc.platform.db.DBEntity

data class User(
    val id: Long = -1,
    val username: String,
    val email: String,
    val registeredIp: String,
    val permissionGroupId: Long = -1,
    val registerDate: Long = System.currentTimeMillis(),
    val lastLoginDate: Long = System.currentTimeMillis(),
    val emailVerified: Boolean = false,
    val banned: Boolean = false,
    val canCreateTicket: Boolean = true,
    val lastActivityTime: Long = System.currentTimeMillis(),
    val lastPanelActivityTime: Long = System.currentTimeMillis(),
) : DBEntity()