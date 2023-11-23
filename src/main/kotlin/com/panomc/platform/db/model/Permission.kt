package com.panomc.platform.db.model

import com.panomc.platform.db.DBEntity

data class Permission(
    val id: Long = -1,
    val name: String,
    val iconName: String
) : DBEntity()