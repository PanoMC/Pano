package com.panomc.platform.db.model

import com.panomc.platform.db.DBEntity

data class PanelConfig(
    val id: Long = -1,
    val userId: Long,
    val option: String,
    val value: String
) : DBEntity()