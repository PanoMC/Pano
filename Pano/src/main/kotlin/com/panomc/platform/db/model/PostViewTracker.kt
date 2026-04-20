package com.panomc.platform.db.model

import com.panomc.platform.db.DBEntity

data class PostViewTracker(
    val id: Long = -1,
    val postId: Long,
    val viewerHash: String,
    val lastViewedAt: Long = System.currentTimeMillis(),
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) : DBEntity()
