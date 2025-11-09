package com.panomc.platform.db.model

import com.panomc.platform.db.DBEntity
import com.panomc.platform.server.ServerStatus
import com.panomc.platform.server.ServerType
import io.vertx.core.json.JsonObject

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
    val stopTime: Long = 0,
    val aesKey: String,
    var settings: ServerSettings = ServerSettings()
) : DBEntity() {
    override fun hashCode(): Int {
        return id.hashCode()
    }

    override fun equals(other: Any?): Boolean {
        return other is Server && other.id == this.id
    }

    companion object {
        data class ServerSettings(
            var authIntegration: Boolean = true,
            var authRequireVerified: Boolean = true,
            var authKickAfterRegister: Boolean = true,
            var banIntegration: Boolean = true,
            var permissionIntegration: Boolean = true
        ) {
            fun encode(): String = JsonObject.mapFrom(this).encode()
        }
    }
}