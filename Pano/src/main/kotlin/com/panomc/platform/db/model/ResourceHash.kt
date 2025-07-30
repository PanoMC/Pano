package com.panomc.platform.db.model

import com.panomc.platform.db.DBEntity
import com.panomc.platform.util.ResourceHashStatus

data class ResourceHash(
    val id: Long = -1,
    val hash: String,
    val status: ResourceHashStatus,
) : DBEntity()