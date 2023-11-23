package com.panomc.platform.db.model

import com.panomc.platform.db.DBEntity
import com.panomc.platform.server.ServerStatus
import com.panomc.platform.server.ServerType

data class Server(
    val id: Long = -1,
    val name: String,
    val motd: String,
    val host: String,
    val port: Int,
    val playerCount: Long,
    val maxPlayerCount: Long,
    val type: ServerType,
    val version: String,
    val favicon: String,
    val permissionGranted: Boolean = false,
    val status: ServerStatus,
    val addedTime: Long = System.currentTimeMillis(),
    val acceptedTime: Long = 0,
    val startTime: Long,
    val stopTime: Long = 0
) : DBEntity()