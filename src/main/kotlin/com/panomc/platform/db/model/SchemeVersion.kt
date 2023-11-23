package com.panomc.platform.db.model

import com.panomc.platform.db.DBEntity

data class SchemeVersion(
    val key: String,
    val extra: String? = null
) : DBEntity()