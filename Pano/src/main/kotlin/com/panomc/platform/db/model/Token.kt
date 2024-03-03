package com.panomc.platform.db.model

import com.panomc.platform.db.DBEntity
import com.panomc.platform.token.TokenType

data class Token(
    val id: Long = -1,
    val subject: String,
    val token: String,
    val type: TokenType,
    val expireDate: Long,
    val startDate: Long = System.currentTimeMillis()
) : DBEntity()