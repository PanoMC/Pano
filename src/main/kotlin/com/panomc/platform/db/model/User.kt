package com.panomc.platform.db.model

data class User(
    val id: Int,
    val username: String,
    val email: String,
    val password: String,
    val registeredIp: String,
    val permissionGroupID: Int = 0,
    val registerDate: Long,
    val emailVerified: Int = 0,
    val banned: Int = 0
)