package com.panomc.platform.db.model

import com.panomc.platform.db.DBEntity

data class PermissionGroup(
    val id: Long = -1,
    val name: String
) : DBEntity()