package com.panomc.platform.config

import com.google.gson.GsonBuilder
import com.google.gson.annotations.SerializedName
import com.panomc.platform.Main.Companion.IS_DEV
import com.panomc.platform.api.config.ConfigComment
import com.panomc.platform.api.config.ConfigSection
import com.panomc.platform.Main.Companion.STAGE
import com.panomc.platform.ReleaseStage
import com.panomc.platform.util.KeyGeneratorUtil
import com.panomc.platform.util.UpdatePeriod
import com.panomc.platform.util.deserializer.UpdatePeriodDeserializer
import io.vertx.core.json.JsonObject
import java.net.URI
import java.util.*

data class PanoConfig(
    @ConfigComment("Configuration version used for migrations (DO NOT manually change).")
    @SerializedName("config-version") var version: Int,

    @ConfigComment("Enable or disable development mode (default: false).")
    @SerializedName("development-mode") var developmentMode: Boolean = false,

    @ConfigComment("Interface language code, e.g. \"en-US\" or \"tr\" (languages can be added in the panel).")
    var locale: String = "en-US",

    @ConfigComment("Allow users to choose their own language from available locales (default: true).")
    @SerializedName("allow-user-locale-selection") var allowUserLocaleSelection: Boolean = true,

    @ConfigComment("Website display name.")
    @SerializedName("website-name") var websiteName: String = "",

    @ConfigComment("Short description of the website (used in metadata).")
    @SerializedName("website-description") var websiteDescription: String = "",

    @ConfigComment("Public URL of your website. Required for emails, cookies and other platform features.")
    @SerializedName("website-url") var websiteUrl: String = "",

    @ConfigComment("If true, requests to non-canonical hosts are redirected to website-url.")
    @SerializedName("website-url-redirect") var websiteUrlRedirect: Boolean = true,

    @ConfigComment("Registration agreement shown to users (supports HTML).")
    @SerializedName("register-agreement") var registerAgreement: String = "",

    @ConfigComment("Support email used for notifications and password resets.")
    @SerializedName("support-email") var supportEmail: String = "",

    @ConfigComment("Minecraft server IP shown to players; they can copy it to connect.")
    @SerializedName("server-ip-address") var serverIpAddress: String = "play.ipadress.com",

    @ConfigComment("Minecraft server game version shown in the theme (e.g. \"1.8.x\").")
    @SerializedName("server-game-version") var serverGameVersion: String = "1.8.x",

    @ConfigComment("SEO keywords for the website.")
    var keywords: List<String> = emptyList(),

    @ConfigSection("Setup Progress (Internal)")
    @ConfigComment(
        "Tracks installation progress. Always stop Pano before editing.",
        "Only modify if instructed by support; improper edits may break the installation."
    )
    var setup: SetupConfig = SetupConfig(),

    @ConfigSection("Database")
    @ConfigComment("Warning: changing type or prefix after installation is not supported and requires a reinstall.")
    var database: DatabaseConfig = DatabaseConfig(),

    @ConfigSection("Pano Account (managed by Pano — do not edit manually)")
    @ConfigComment(
        "Required for Marketplace features (updates, store installs).",
        "Manage linking in Panel → Settings → Platform."
    )
    @SerializedName("pano-account") var panoAccount: PanoAccountConfig = PanoAccountConfig(),

    @ConfigComment("Active theme ID; falls back to \"vanilla-theme\" if invalid. Change via Panel → View → Themes.")
    @SerializedName("current-theme") var currentTheme: String = "vanilla-theme",

    @ConfigSection("Email (SMTP)")
    @ConfigComment(
        "Optional during setup; configurable later via Panel → Settings → Platform.",
        "Without SMTP, password recovery and verification emails will not work."
    )
    var email: EmailConfig = EmailConfig(),

    @ConfigSection("Server")
    @ConfigComment("Network bindings and TLS. For complex setups a reverse proxy (Nginx, Apache, Cloudflare) also works.")
    var server: ServerConfig = ServerConfig(),

    @ConfigComment("Launches the setup wizard, panel and theme engine at startup.")
    @SerializedName("init-ui") var initUi: Boolean = true,

    @ConfigComment("Auto-generated Base64 authentication key — DO NOT modify manually.")
    @SerializedName("jwt-key") var jwtKey: String = generateJwtKey(),

    @ConfigComment("Update check frequency: ONCE_PER_DAY, ONCE_PER_WEEK or ONCE_PER_MONTH.")
    @SerializedName("update-period") var updatePeriod: UpdatePeriod = UpdatePeriod.ONCE_PER_DAY,

    @ConfigComment(
        "Update stream to follow:",
        "  ALPHA   — early access, high risk of bugs and breaking changes.",
        "  BETA    — pre-release, lower risk than alpha but may still have bugs.",
        "  RELEASE — most stable, less frequent updates.",
        "Note: \"stable\" maps to ReleaseStage.RELEASE in code."
    )
    @SerializedName("release-channel") var releaseChannel: ReleaseStage = STAGE,

    @ConfigComment("Folder where user-uploaded files are stored.")
    @SerializedName("file-uploads-folder") var fileUploadsFolder: String = "file-uploads",

    @ConfigSection("File Paths (auto-managed — do not edit manually)")
    @ConfigComment(
        "Controlled by Panel → Settings → Website.",
        "Only \"faviconFile\" and \"websiteLogoFile\" are supported.",
        "Each entry has: path (relative file path) and hash (SHA-256 integrity check)."
    )
    @SerializedName("file-paths") var filePaths: FilePaths = FilePaths(),

    @ConfigSection("Pano Service URLs (managed automatically — DO NOT modify)")
    @ConfigComment("Changing these can break connectivity with the Pano ecosystem.")
    @SerializedName("pano-api-url") var panoApiUrl: String = PANO_API_URL_PRODUCTION,

    @SerializedName("pano-website-url") var panoWebsiteUrl: String = PANO_WEBSITE_URL_PRODUCTION,

    @ConfigComment("Enables the Pano MC plugin authentication endpoint. Disable for added security if unused.")
    @SerializedName("accept-plugin-auth") var acceptPluginAuth: Boolean = true,

    @ConfigComment("Max commands stored in terminal and GUI console history (0 to disable, default: 50).")
    @SerializedName("console-history-limit") var consoleHistoryLimit: Int = 50,

    @ConfigSection("Authentication")
    val auth: AuthConfig = AuthConfig(),
) {
    /**
     * JWT `iss` plugins expect when verifying license tokens. No extra config key: uses the
     * hostname of [panoWebsiteUrl] (scheme and port stripped, e.g. `https://dev.panomc.com` →
     * `dev.panomc.com`, `https://local.panomc.com:3003` → `local.panomc.com`). If the website URL
     * is missing or unparsable, falls back to [panoApiUrl] via [issuerHintFromLicenseApiHost]
     * (`api.panomc.com` → `panomc.com`, `api-dev.panomc.com` → `dev.panomc.com`, else the API host).
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
        data class SetupConfig(
            @ConfigComment("0 = restart setup wizard, 5 = setup complete.")
            var step: Int = 0
        )

        data class DatabaseConfig(
            @ConfigComment("\"mariadb\" (MySQL/MariaDB) or \"portable\" (Windows x64/ARM64 only, auto-managed by Pano).")
            var type: String = "mariadb",

            @ConfigComment("Database host, e.g. \"127.0.0.1:3306\".")
            var host: String = "",

            @ConfigComment("Database name.")
            var name: String = "",

            var username: String = "",

            @ConfigComment("Can be empty if your database has no password.")
            var password: String? = "",

            @ConfigComment("Table prefix (do not change after installation).")
            var prefix: String = "pano_"
        )

        data class PanoAccountConfig(
            var username: String = "",
            var email: String = "",

            @ConfigComment("Secure token for your Pano account.")
            @SerializedName("access-token") var accessToken: String = "",

            @ConfigComment("Pano account ID.")
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

            @ConfigComment("Sender, e.g. \"Pano <no-reply@domain.com>\" — usually the same as username.")
            var sender: String = "",

            @ConfigComment("SMTP host, e.g. \"smtp.gmail.com\".")
            var hostname: String = "",

            var port: Int = 465,

            @ConfigComment("Username, e.g. \"no-reply@domain.com\".")
            var username: String = "",

            var password: String = "",

            var ssl: Boolean = true,

            @ConfigComment("\"DISABLED\", \"OPTIONAL\" or \"REQUIRED\".")
            var starttls: String = "",

            @ConfigComment("Optional, mostly \"PLAIN\".")
            var authMethods: String = ""
        )

        data class ServerConfig(
            @ConfigComment("\"0.0.0.0\" exposes the panel externally; \"127.0.0.1\" restricts to local only.")
            var host: String = "0.0.0.0",

            @ConfigComment("HTTP port (usually 80).")
            @SerializedName("http-port") var httpPort: Int = if (IS_DEV) 8088 else 80,

            @ConfigComment("HTTPS port (usually 443).")
            @SerializedName("https-port") var httpsPort: Int = if (IS_DEV) 8443 else 443,

            @ConfigComment(
                "TLS mode:",
                "  DISABLED     — no HTTPS server.",
                "  LETS_ENCRYPT — auto-obtains a certificate (requires website-url, http-port=80, https-port=443).",
                "  MANUAL       — use ssl-cert and ssl-key below."
            )
            @SerializedName("ssl-mode") var sslMode: SslMode = SslMode.DISABLED,

            @ConfigComment("If true, all HTTP traffic is redirected to HTTPS.")
            @SerializedName("redirect-https") var redirectHttps: Boolean = false,

            @ConfigComment("Raw certificate content (only when ssl-mode = MANUAL).")
            @SerializedName("ssl-cert") var sslCert: String? = null,

            @ConfigComment("Raw private key content (only when ssl-mode = MANUAL).")
            @SerializedName("ssl-key") var sslKey: String? = null
        )

        data class AuthConfig(
            @ConfigComment("Require email verification before allowing login.")
            @SerializedName("require-email-verification") var requireEmailVerification: Boolean = true,

            @ConfigComment("Password hashing algorithm.")
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

        const val PANO_API_URL_PRODUCTION = "https://api.panomc.com"
        const val PANO_API_URL_DEVELOPMENT = "https://api-dev.panomc.com"
        const val PANO_WEBSITE_URL_PRODUCTION = "https://panomc.com"
        const val PANO_WEBSITE_URL_DEVELOPMENT = "https://dev.panomc.com"

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
