package com.panomc.platform.db.model

import com.panomc.platform.db.DBEntity
import com.panomc.platform.server.ServerStatus
import com.panomc.platform.server.ServerType
import io.vertx.core.json.JsonObject

data class Server(
    val id: Long = -1,
    var name: String,
    var motd: String,
    var host: String,
    var remoteAddress: String? = null,
    var port: Int,
    var playerCount: Long,
    var maxPlayerCount: Long,
    var type: ServerType,
    var version: String,
    var favicon: String,
    val permissionGranted: Boolean = false,
    var status: ServerStatus,
    val addedTime: Long = System.currentTimeMillis(),
    val acceptedTime: Long = 0,
    var startTime: Long,
    val stopTime: Long = 0,
    val aesKey: String,
    var settings: ServerSettings = ServerSettings(),
    var customName: String? = null
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