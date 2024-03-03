package com.panomc.platform.db.model

import com.panomc.platform.db.DBEntity
import com.panomc.platform.util.DateUtil

data class WebsiteView(
    val id: Long = -1,
    val times: Long = 1,
    val date: Long = DateUtil.getTodayInMillis(),
    val ipAddress: String
) : DBEntity()