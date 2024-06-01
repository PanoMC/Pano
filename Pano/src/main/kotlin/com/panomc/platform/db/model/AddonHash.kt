package com.panomc.platform.db.model

import com.panomc.platform.db.DBEntity
import com.panomc.platform.util.AddonHashStatus

data class AddonHash(
    val id: Long = -1,
    val hash: String,
    val status: AddonHashStatus,
) : DBEntity()