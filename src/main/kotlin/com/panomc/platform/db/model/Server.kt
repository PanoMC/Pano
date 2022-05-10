package com.panomc.platform.db.model

data class Server(
    val id: Int,
    val name: String,
    val playerCount: Int,
    val maxPlayerCount: Int,
    val type: String,
    val version: String,
    val favicon: String,
    val status: String
)