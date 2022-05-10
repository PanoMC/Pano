package com.panomc.platform.db.model

data class PostCategory(
    val id: Int = -1,
    val title: String = "-",
    val description: String = "",
    val url: String = "",
    val color: String = ""
)