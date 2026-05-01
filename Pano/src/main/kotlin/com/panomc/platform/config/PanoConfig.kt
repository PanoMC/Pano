package com.panomc.platform.config

import com.google.gson.GsonBuilder
import com.google.gson.annotations.SerializedName
import com.panomc.platform.Main.Companion.IS_DEV
import com.panomc.platform.Main.Companion.STAGE
import com.panomc.platform.ReleaseStage
import com.panomc.platform.util.KeyGeneratorUtil
import com.panomc.platform.util.UpdatePeriod
import com.panomc.platform.util.deserializer.UpdatePeriodDeserializer
import io.vertx.core.json.JsonObject
import java.net.URI
import java.util.*

data class PanoConfig(
    @SerializedName("config-version") var version: Int,
    @SerializedName("development-mode") var developmentMode: Boolean = false,
    var locale: String = "en-US",

    @SerializedName("allow-user-locale-selection") var allowUserLocaleSelection: Boolean = true,

    @SerializedName("website-name") var websiteName: String = "",
    @SerializedName("website-description") var websiteDescription: String = "",
    @SerializedName("website-url") var websiteUrl: String = "",
    @SerializedName("register-agreement") var registerAgreement: String = "",
    @SerializedName("support-email") var supportEmail: String = "",
    @SerializedName("server-ip-address") var serverIpAddress: String = "play.ipadress.com",
    @SerializedName("server-game-version") var serverGameVersion: String = "1.8.x",
    var keywords: List<String> = emptyList(),

    var setup: SetupConfig = SetupConfig(),

    var database: DatabaseConfig = DatabaseConfig(),

    @SerializedName("pano-account") var panoAccount: PanoAccountConfig = PanoAccountConfig(),

    @SerializedName("current-theme") var currentTheme: String = "vanilla-theme",

    var email: EmailConfig = EmailConfig(),

    var server: ServerConfig = ServerConfig(),

    @SerializedName("init-ui") var initUi: Boolean = true,

    @SerializedName("jwt-key") var jwtKey: String = generateJwtKey(),

    @SerializedName("update-period") var updatePeriod: UpdatePeriod = UpdatePeriod.ONCE_PER_DAY,

    // Which release channel to track for platform updates (alpha/beta/stable)
    // NOTE: "stable" maps to ReleaseStage.RELEASE in code.
    @SerializedName("release-channel") var releaseChannel: ReleaseStage = STAGE,

    @SerializedName("file-uploads-folder") var fileUploadsFolder: String = "file-uploads",
    @SerializedName("file-paths") var filePaths: FilePaths = FilePaths(),

    @SerializedName("pano-api-url") var panoApiUrl: String = getPanoApiUrl(),
    @SerializedName("pano-website-url") var panoWebsiteUrl: String = getPanoWebsiteUrl(),

    @SerializedName("accept-plugin-auth") var acceptPluginAuth: Boolean = true,
    @SerializedName("console-history-limit") var consoleHistoryLimit: Int = 50,

    val auth: AuthConfig = AuthConfig(),
) {
    /**
     * JWT `iss` plugins should expect — no extra config key: hostname of [panoWebsiteUrl] if set,
     * else derived from [panoApiUrl] (`api.panomc.com` → `panomc.com`, `api-dev.panomc.com` → `dev.panomc.com`,
     * otherwise the API hostname). Must match `licensing.issuer` on the license API.
     */
    fun resolvedLicenseJwtIssuer(): String {
        hostnameFromHttpUrl(panoWebsiteUrl.trim())?.takeIf { it.isNotBlank() }?.let { return it }
        val apiHost = hostnameFromHttpUrl(panoApiUrl.trim())
        if (!apiHost.isNullOrBlank()) {
            val fromApi = issuerHintFromLicenseApiHost(apiHost)
            if (fromApi.isNotBlank()) return fromApi
        }
        return "panomc.com"
    }

    companion object {
        data class SetupConfig(var step: Int = 0)

        data class DatabaseConfig(
            var type: String = "mariadb",
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
            var enabled: Boolean = false,
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
            @SerializedName("http-port") var httpPort: Int = if (IS_DEV) 8088 else 80,
            @SerializedName("https-port") var httpsPort: Int = if (IS_DEV) 8443 else 443,
            @SerializedName("ssl-mode") var sslMode: SslMode = SslMode.DISABLED,
            @SerializedName("redirect-https") var redirectHttps: Boolean = false,
            @SerializedName("ssl-cert") var sslCert: String? = null,
            @SerializedName("ssl-key") var sslKey: String? = null
        )

        data class AuthConfig(
            @SerializedName("require-email-verification") var requireEmailVerification: Boolean = true,
            @SerializedName("password-hash-algorithm") var passwordHashAlgorithm: String = "ARGON2ID",
        )

        enum class SslMode {
            DISABLED,
            LETS_ENCRYPT,
            MANUAL
        }

        data class FilePaths(
            var websiteLogoFile: FileInfo? = null,
            var faviconFile: FileInfo? = null
        )

        data class FileInfo(
            val path: String,
            val hash: String
        )

        private fun generateJwtKey(): String {
            val key = KeyGeneratorUtil.generateJWTKey()

            return String(Base64.getEncoder().encode(key.toByteArray()))
        }

        private fun getPanoApiUrl() =
            if (IS_DEV) "https://api-dev.panomc.com"
            else "https://api.panomc.com"

        private fun getPanoWebsiteUrl() =
            if (IS_DEV) "https://dev.panomc.com"
            else "https://panomc.com"

        private val gson = GsonBuilder()
            .registerTypeAdapter(UpdatePeriod::class.java, UpdatePeriodDeserializer())
            .create()

        fun from(jsonObject: JsonObject) = gson.fromJson(jsonObject.encode(), PanoConfig::class.java)
    }

    override fun toString(): String = gson.toJson(this)
}

private fun hostnameFromHttpUrl(raw: String): String? {
    if (raw.isEmpty()) return null
    return try {
        val normalized = if (raw.contains("://")) raw else "https://$raw"
        URI(normalized).host?.takeIf { it.isNotBlank() }
    } catch (_: Exception) {
        null
    }
}

/**
 * Maps the license API host to the usual site `iss` for panomc deployments; otherwise returns the API host.
 */
private fun issuerHintFromLicenseApiHost(apiHost: String): String {
    Regex("^api\\.(.+)$").matchEntire(apiHost)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() }?.let {
        return it
    }
    Regex("^api-([^.]+)\\.(.+)$").matchEntire(apiHost)?.let { m ->
        val label = m.groupValues[1].trim()
        val rest = m.groupValues[2].trim()
        if (label.isNotEmpty() && rest.isNotEmpty()) return "$label.$rest"
    }
    return apiHost.trim()
}