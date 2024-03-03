package com.panomc.platform.db.model

import com.panomc.platform.db.DBEntity

data class PermissionGroupPerms(
    val id: Long = -1,
    val permissionId: Long,
    val permissionGroupId: Long
) : DBEntity()