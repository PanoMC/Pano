package com.panomc.platform.db.model


import com.panomc.platform.db.DBEntity

data class SystemProperty(
    val id: Long = -1,
    val option: String,
    val value: String = ""
) : DBEntity()