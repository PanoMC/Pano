package com.panomc.platform.config

import com.google.gson.GsonBuilder
import com.google.gson.annotations.SerializedName
import com.panomc.platform.Main
import com.panomc.platform.ReleaseStage
import com.panomc.platform.util.KeyGeneratorUtil
import com.panomc.platform.util.UpdatePeriod
import com.panomc.platform.util.deserializer.UpdatePeriodDeserializer
import io.vertx.core.json.JsonObject
import java.util.*

data class PanoConfig(
    @SerializedName("config-version") var version: Int,
    @SerializedName("development-mode") var developmentMode: Boolean = true,
    var locale: String = "en-US",

    @SerializedName("website-name") var websiteName: String = "",
    @SerializedName("website-description") var websiteDescription: String = "",
    @SerializedName("support-email") var supportEmail: String = "",
    @SerializedName("server-ip-address") var serverIpAddress: String = "play.ipadress.com",
    @SerializedName("server-game-version") var serverGameVersion: String = "1.8.x",
    var keywords: List<String> = emptyList(),

    var setup: SetupConfig = SetupConfig(),

    var database: DatabaseConfig = DatabaseConfig(),

    @SerializedName("pano-account") var panoAccount: PanoAccountConfig = PanoAccountConfig(),

    @SerializedName("current-theme") var currentTheme: String = "Vanilla",

    var email: EmailConfig = EmailConfig(),

    var server: ServerConfig = ServerConfig(),

    @SerializedName("init-ui") var initUi: Boolean = true,

    @SerializedName("jwt-key") var jwtKey: String = generateJwtKey(),

    @SerializedName("update-period") var updatePeriod: UpdatePeriod = UpdatePeriod.ONCE_PER_DAY,

    @SerializedName("ui-address") var uiAddress: String = "http://localhost:3000",
    @SerializedName("file-uploads-folder") var fileUploadsFolder: String = "file-uploads",
    @SerializedName("file-paths") var filePaths: MutableMap<String, String> = mutableMapOf(),

    @SerializedName("pano-api-url") var panoApiUrl: String = getPanoApiUrl(),
) {
    companion object {
        data class SetupConfig(var step: Int = 0)

        data class DatabaseConfig(
            var host: String = "",
            var name: String = "",
            var username: String = "",
            var password: String? = "",
            var prefix: String = "pano_"
        )

        data class PanoAccountConfig(
            var username: String = "",
            var email: String = "",
            @SerializedName("access-token") var accessToken: String = "",
            @SerializedName("platform-id") var platformId: String = "",
            var connect: PanoAccountConnectConfig? = null
        )

        data class PanoAccountConnectConfig(
            @SerializedName("public-key") var publicKey: String,
            @SerializedName("private-key") var privateKey: String,
            var state: String
        )

        data class EmailConfig(
            var sender: String = "",
            var hostname: String = "",
            var port: Int = 465,
            var username: String = "",
            var password: String = "",
            var ssl: Boolean = true,
            var starttls: String = "",
            var authMethods: String = ""
        )

        data class ServerConfig(
            var host: String = "0.0.0.0",
            var port: Int = 8088
        )

        private fun generateJwtKey(): String {
            val key = KeyGeneratorUtil.generateJWTKey()

            return Base64.getEncoder().encode(key.toByteArray()).toString()
        }

        private fun getPanoApiUrl() =
            "api" + (if (Main.STAGE == ReleaseStage.ALPHA || Main.STAGE == ReleaseStage.BETA) "-dev" else "") + ".panomc.com"

        private val gson = GsonBuilder()
            .registerTypeAdapter(UpdatePeriod::class.java, UpdatePeriodDeserializer())
            .create()

        fun from(jsonObject: JsonObject) = gson.fromJson(jsonObject.encode(), PanoConfig::class.java)
    }

    override fun toString(): String = gson.toJson(this)
}