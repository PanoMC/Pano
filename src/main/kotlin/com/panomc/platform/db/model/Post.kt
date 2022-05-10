package com.panomc.platform.db.model

data class Post(
    val id: Int,
    val title: String,
    val categoryId: Int,
    val writerUserID: Int,
    val text: String,
    val date: Long,
    val moveDate: Long,
    val status: Int,
    val image: String,
    val views: Long
)