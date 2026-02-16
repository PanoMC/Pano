package com.panomc.platform.api.event

import com.panomc.platform.db.model.User

interface PlayerEventListener : PanoEventListener {
    suspend fun onDelete(user: User) {}
}
