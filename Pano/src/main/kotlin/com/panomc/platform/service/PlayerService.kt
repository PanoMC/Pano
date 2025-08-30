package com.panomc.platform.service

import com.panomc.platform.model.Result
import com.panomc.platform.util.PlayerStatus

interface PlayerService {
    suspend fun getPlayers(playerStatus: PlayerStatus, page: Long, permissionGroupName: String?): Result
}