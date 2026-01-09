package com.panomc.platform.db.model

import com.panomc.platform.db.DBEntity
import java.util.*

data class SchemeVersion(
    val pluginId: String? = null,
    val `when`: String = Date().toString(),
    val key: String,
    val extra: String? = null
) : DBEntity()