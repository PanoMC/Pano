package com.panomc.platform.db.model

import com.panomc.platform.db.DBEntity

data class OnlinePlayerHistory(
    val id: Long = -1,
    val date: Long = 0,
    val maxCount: Long = 0,
    val sumCount: Long = 0,
    val sampleCount: Long = 0,
    val lastRecordedAt: Long = 0,
) : DBEntity()
