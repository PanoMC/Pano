package com.panomc.platform.db.model

import com.panomc.platform.db.DBEntity

data class PostCategory(
    val id: Long = -1,
    val title: String = "-",
    val description: String = "",
    val url: String = "-",
    val color: String = ""
) : DBEntity()