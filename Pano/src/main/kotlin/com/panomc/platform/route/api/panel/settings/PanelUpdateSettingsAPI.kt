package com.panomc.platform.route.api.panel.settings

import com.panomc.platform.PlatformStateManager
import com.panomc.platform.ReleaseStage
import com.panomc.platform.UpdateManager
import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.panel.log.MaintenanceModeToggledLog
import com.panomc.platform.auth.panel.permission.ManagePlatformSettingsPermission
import com.panomc.platform.config.ConfigManager
import com.panomc.platform.config.PanoConfig
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.Translation.Companion.TranslationType
import com.panomc.platform.error.*
import com.panomc.platform.i18n.I18nManager
import com.panomc.platform.maintenance.MaintenanceModeManager
import com.panomc.platform.model.*
import com.panomc.platform.server.ServerManager
import com.panomc.platform.server.response.GetServerSettingsEventResponse
import com.panomc.platform.util.FileUploadUtil
import com.panomc.platform.util.HashUtil.hash
import com.panomc.platform.util.UpdatePeriod
import com.panomc.platform.util.WebsiteUrlUtil
import io.vertx.ext.mail.StartTLSOptions
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.RequestPredicate
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Bodies
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.*
import org.imgscalr.Scalr
import java.io.File
import javax.imageio.ImageIO

@Endpoint
class PanelUpdateSettingsAPI(
    private val configManager: ConfigManager,
    private val authProvider: AuthProvider,
    private val databaseManager: DatabaseManager,
    private val platformStateManager: PlatformStateManager,
    private val updateManager: UpdateManager,
    private val serverManager: ServerManager,
    private val i18nManager: I18nManager,
    private val maintenanceModeManager: MaintenanceModeManager,
) : PanelApi() {
    override val paths = listOf(Path("/api/panel/settings", RouteType.PUT))

    private val defaultWebsiteUploadPath = "website"

    private val acceptedFileFields = listOf(
        FileUploadUtil.Field(
            name = "favicon",
            fieldConfig = FileUploadUtil.FieldConfig(
                path = "$defaultWebsiteUploadPath/favicon",
                acceptedContentTypes = listOf(
                    "image/x-icon",
                    "image/vnd.microsoft.icon",
                    "image/png",
                    "image/gif",
                    "image/jpeg",
                    "image/webp"
                ),
                contentTypeError = FaviconWrongContentType(),
                fileSizeError = FaviconExceedsSize(),
                withTempName = false,
                size = 1024 * 1024 // 1 MB
            )
        ),
        FileUploadUtil.Field(
            name = "websiteLogo",
            fieldConfig = FileUploadUtil.FieldConfig(
                path = "$defaultWebsiteUploadPath/website-logo",
                acceptedContentTypes = listOf(
                    "image/png",
                    "image/jpeg",
                    "image/gif",
                    "image/webp"
                ),
                contentTypeError = WebsiteLogoWrongContentType(),
                fileSizeError = WebsiteLogoExceedsSize(),
                withTempName = false,
                size = 2 * 1024 * 1024 // 2 MB
            )
        )
    )

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .body(
                Bodies.multipartFormData(
                    objectSchema()
                        .optionalProperty(
                            "updatePeriod",
                            enumSchema(*UpdatePeriod.entries.map { it.name }.toTypedArray())
                        )
                        .optionalProperty(
                            "releaseChannel",
                            enumSchema(*ReleaseStage.entries.map { it.name }.toTypedArray())
                        )
                        .optionalProperty("locale", stringSchema())
                        .optionalProperty("allowUserLocaleSelection", booleanSchema())
                        .optionalProperty("developmentMode", booleanSchema())
                        .optionalProperty("websiteName", stringSchema())
                        .optionalProperty("websiteDescription", stringSchema())
                        .optionalProperty("websiteUrl", stringSchema())
                        .optionalProperty("registerAgreement", stringSchema())
                        .optionalProperty("supportEmail", stringSchema())
                        .optionalProperty("serverIpAddress", stringSchema())
                        .optionalProperty("serverGameVersion", stringSchema())
                        .optionalProperty("keywords", arraySchema().items(stringSchema()))
                        .optionalProperty("httpPort", intSchema())
                        .optionalProperty("httpsPort", intSchema())
                        .optionalProperty("sslCert", stringSchema())
                        .optionalProperty("sslKey", stringSchema())
                        .optionalProperty(
                            "sslMode",
                            enumSchema(*PanoConfig.Companion.SslMode.entries.map { it.name }.toTypedArray())
                        )
                        .optionalProperty("redirectHttps", booleanSchema())
                        .optionalProperty(
                            "email",
                            objectSchema()
                                .requiredProperty("enabled", booleanSchema())
                                .requiredProperty("hostname", stringSchema())
                                .requiredProperty("port", intSchema())
                                .requiredProperty("ssl", booleanSchema())
                                .requiredProperty(
                                    "starttls",
                                    enumSchema(*StartTLSOptions.entries.map { it.name }.toTypedArray())
                                )
                                .requiredProperty("username", stringSchema())
                                .requiredProperty("password", stringSchema())
                                .requiredProperty("sender", stringSchema())
                                .optionalProperty("authMethods", stringSchema())
                        )
                        .optionalProperty(
                            // One nested object, like "email" above, so the maintenance card can
                            // never be half-applied.
                            "maintenance",
                            objectSchema()
                                .requiredProperty("enabled", booleanSchema())
                                .requiredProperty("bypassPermissionNode", stringSchema())
                                .requiredProperty("showLoginButton", booleanSchema())
                                .requiredProperty("customLoginUrl", stringSchema())
                                .requiredProperty("showSiteLogo", booleanSchema())
                                .requiredProperty("title", stringSchema())
                                .requiredProperty("messageHtml", stringSchema())
                                .requiredProperty("customCss", stringSchema())
                        )
                        .optionalProperty("password", stringSchema())
                        .optionalProperty("requireEmailVerification", booleanSchema())
                        .optionalProperty("passwordHashAlgorithm", enumSchema("ARGON2ID", "BCRYPT", "SHA256", "MD5"))
                )
            )
            .predicate(RequestPredicate.BODY_REQUIRED)
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        authProvider.requirePermission(ManagePlatformSettingsPermission(), context)

        val parameters = getParameters(context)
        val data = parameters.body().jsonObject

        val fileUploads = context.fileUploads()

        val updatePeriod =
            if (data.getString("updatePeriod") == null) null else UpdatePeriod.valueOf(data.getString("updatePeriod"))
        val releaseChannel =
            if (data.getString("releaseChannel") == null) null else ReleaseStage.valueOf(data.getString("releaseChannel"))
        val locale = data.getString("locale")
        val allowUserLocaleSelection = data.getBoolean("allowUserLocaleSelection")
        val developmentMode = data.getBoolean("developmentMode")
        val websiteName = data.getString("websiteName")
        val websiteDescription = data.getString("websiteDescription")
        val websiteUrl = data.getString("websiteUrl")
        val registerAgreement = data.getString("registerAgreement")
        val supportEmail = data.getString("supportEmail")
        val serverIpAddress = data.getString("serverIpAddress")
        val serverGameVersion = data.getString("serverGameVersion")
        val keywords = context.request().getFormAttribute("keywords")?.split(",")

        val httpPort = data.getInteger("httpPort")
        val httpsPort = data.getInteger("httpsPort")
        val sslMode = if (data.getString("sslMode") == null) null else PanoConfig.Companion.SslMode.valueOf(data.getString("sslMode"))
        val sslCert = data.getString("sslCert")
        val sslKey = data.getString("sslKey")
        val redirectHttps = data.getBoolean("redirectHttps")

        val password = data.getString("password")

        val email = data.getJsonObject("email")

        val requireEmailVerification = data.getBoolean("requireEmailVerification")
        val passwordHashAlgorithm = data.getString("passwordHashAlgorithm")

        val maintenance = data.getJsonObject("maintenance")

        if (fileUploads.isNotEmpty()) {
            val savedFiles = FileUploadUtil.saveFiles(fileUploads, acceptedFileFields, configManager)

            savedFiles.forEach { savedFile ->
                val filePathsInConfig = configManager.config.filePaths

                val fileInfo = when (savedFile.field.name) {
                    "favicon" -> filePathsInConfig.faviconFile
                    "websiteLogo" -> filePathsInConfig.websiteLogoFile
                    else -> throw BadRequest()
                }

                if (fileInfo != null && fileInfo.path != savedFile.path) {
                    val oldFile = File(
                        configManager.config
                            .fileUploadsFolder + File.separator + fileInfo.path
                    )

                    if (oldFile.exists()) {
                        oldFile.delete()
                    }
                }

                if (savedFile.field.name == "favicon" && !savedFile.path.endsWith(".gif")) {
                    try {
                        val file = File(
                            configManager.config
                                .fileUploadsFolder + File.separator + savedFile.path
                        )
                        file.inputStream().use { input ->
                            val original = ImageIO.read(input)
                            val resized = Scalr.resize(original, 128, 128)

                            ImageIO.write(resized, "PNG", file)
                        }
                    } catch (_: Exception) {
                    }
                }

                when (savedFile.field.name) {
                    "favicon" -> filePathsInConfig.faviconFile =
                        PanoConfig.Companion.FileInfo(
                            savedFile.path,
                            File(
                                configManager.config.fileUploadsFolder + File.separator + savedFile.path
                            ).inputStream().use {it.hash()}
                        )

                    "websiteLogo" -> filePathsInConfig.websiteLogoFile =
                        PanoConfig.Companion.FileInfo(
                            savedFile.path,
                            File(
                                configManager.config.fileUploadsFolder + File.separator + savedFile.path
                            ).inputStream().use {it.hash()}
                        )
                }
            }
        }

        val sqlClient = getSqlClient()

        var updateCheckRequired = false

        if (updatePeriod != null && updatePeriod != configManager.config.updatePeriod) {
            configManager.config.updatePeriod = updatePeriod
        }

        if (releaseChannel != null) {
            if (releaseChannel != configManager.config.releaseChannel) {
                configManager.config.releaseChannel = releaseChannel
                updateCheckRequired = true
            }
        }

        if (updateCheckRequired && configManager.config.updatePeriod != UpdatePeriod.NEVER) {
            updateManager.checkUpdates(true)
        }

        if (locale != null) {
            if (!databaseManager.localeDao.existsByCode(locale, sqlClient)) {
                throw InvalidLocaleCode()
            }

            configManager.config.locale = locale
        }

        if (allowUserLocaleSelection != null) {
            configManager.config.allowUserLocaleSelection = allowUserLocaleSelection
        }

        if (developmentMode != null && developmentMode != configManager.config.developmentMode) {
            configManager.config.developmentMode = developmentMode

            // If development mode is toggled, clear any previous dismissals so they reappear for all users
            val option = "dismissed_dev_mode_alert"
            databaseManager.panelConfigDao.deleteByOption(option, sqlClient)
        }

        if (websiteName != null) {
            configManager.config.websiteName = websiteName
        }

        if (websiteDescription != null) {
            configManager.config.websiteDescription = websiteDescription
        }

        if (websiteUrl != null) {
            // Compare both sides normalized: the stored value may predate normalization (trailing
            // slash, default port), and treating that as a change would demand a password the
            // panel never prompted for.
            val normalizedWebsiteUrl = WebsiteUrlUtil.normalize(websiteUrl)

            if (normalizedWebsiteUrl != WebsiteUrlUtil.normalize(configManager.config.websiteUrl)) {
                authProvider.requirePassword(password, context)
                configManager.config.websiteUrl = normalizedWebsiteUrl
                platformStateManager.restartRequired = true
            }
        }

        if (registerAgreement != null) {
            configManager.config.registerAgreement = registerAgreement
        }

        if (supportEmail != null) {
            configManager.config.supportEmail = supportEmail
        }

        if (serverIpAddress != null) {
            configManager.config.serverIpAddress = serverIpAddress
        }

        if (serverGameVersion != null) {
            configManager.config.serverGameVersion = serverGameVersion
        }

        if (keywords != null) {
            configManager.config.keywords = keywords.filter { !it.isBlank() }
        }

        if (email != null) {
            val mailConfiguration = configManager.config.email

            mailConfiguration.enabled = email.getBoolean("enabled")

            if (email.getBoolean("enabled")) {
                mailConfiguration.sender = email.getString("sender")
                mailConfiguration.hostname = email.getString("hostname")
                mailConfiguration.port = email.getInteger("port")
                mailConfiguration.username = email.getString("username")
                mailConfiguration.password = email.getString("password")
                mailConfiguration.ssl = email.getBoolean("ssl")
                mailConfiguration.starttls = email.getString("starttls")
                mailConfiguration.authMethods = email.getString("authMethods")
            }
        }

        if (httpPort != null && httpPort != configManager.config.server.httpPort) {
            authProvider.requirePassword(password, context)
            configManager.config.server.httpPort = httpPort
            platformStateManager.restartRequired = true
        }

        if (httpsPort != null && httpsPort != configManager.config.server.httpsPort) {
            authProvider.requirePassword(password, context)
            configManager.config.server.httpsPort = httpsPort
            platformStateManager.restartRequired = true
        }

        if (sslMode != null && sslMode != configManager.config.server.sslMode) {
            authProvider.requirePassword(password, context)
            configManager.config.server.sslMode = sslMode
            platformStateManager.restartRequired = true
        }

        if (sslCert != null && sslCert != "****************" && sslCert != configManager.config.server.sslCert) {
            authProvider.requirePassword(password, context)
            configManager.config.server.sslCert = sslCert
            platformStateManager.restartRequired = true
        }

        if (sslKey != null && sslKey != "****************" && sslKey != configManager.config.server.sslKey) {
            authProvider.requirePassword(password, context)
            configManager.config.server.sslKey = sslKey
            platformStateManager.restartRequired = true
        }

        if (redirectHttps != null && redirectHttps != configManager.config.server.redirectHttps) {
            authProvider.requirePassword(password, context)
            configManager.config.server.redirectHttps = redirectHttps
            platformStateManager.restartRequired = true
        }

        if (requireEmailVerification != null) {
            val oldRequireEmailVerification = configManager.config.auth.requireEmailVerification
            configManager.config.auth.requireEmailVerification = requireEmailVerification

            if (oldRequireEmailVerification != requireEmailVerification) {
                // Notify all connected servers
                val translationsByLocale = i18nManager.getTranslationsByLocale(TranslationType.MC_PLUGIN)
                val platformLocale = configManager.config.locale

                serverManager.getConnectedServers().forEach { (server, _) ->
                    val serverSettings = server.settings
                    val responseBody = GetServerSettingsEventResponse(
                        serverSettings.authIntegration,
                        if (!requireEmailVerification) false else serverSettings.authRequireVerified,
                        serverSettings.authKickAfterRegister,
                        serverSettings.banIntegration,
                        serverSettings.permissionIntegration,
                        translationsByLocale,
                        platformLocale
                    )
                    serverManager.sendMessage(responseBody, server)
                }
            }
        }

        if (passwordHashAlgorithm != null) {
            configManager.config.auth.passwordHashAlgorithm = passwordHashAlgorithm
        }

        // Null while the master switch keeps its current position; the toggle is the only part of
        // the card that gets an activity log entry.
        var maintenanceToggledTo: Boolean? = null

        if (maintenance != null) {
            val bypassPermissionNode = maintenance.getString("bypassPermissionNode", "").trim()

            // Wildcards are asymmetric: the node is matched as a literal target, so "admins.*"
            // would only ever match users literally holding "admins.*".
            if (bypassPermissionNode.length > 128 ||
                bypassPermissionNode.contains("*") ||
                bypassPermissionNode.any { it.isWhitespace() }
            ) {
                throw BadRequest()
            }

            val customLoginUrl = maintenance.getString("customLoginUrl", "").trim()

            if (customLoginUrl.isNotEmpty()) {
                // The invariant, not a character allowlist: the router matches on the normalized
                // path, so a value that is not already in normalized form ("//evil.example",
                // "/staff/../entrance", "/entr%61nce") can never match a real request and would
                // leave the maintenance login form unreachable. normalizePath also rejects the API
                // prefixes and anything that loses a query/fragment on the way through, so this one
                // comparison replaces the old per-character rules. Non-ASCII paths such as "/giriş"
                // normalize to themselves and stay allowed.
                if (customLoginUrl.length > 128 ||
                    customLoginUrl.any { it.isWhitespace() || it.isISOControl() } ||
                    maintenanceModeManager.normalizePath(customLoginUrl) != customLoginUrl
                ) {
                    throw BadRequest()
                }
            }

            // A hand-deleted `maintenance { }` block deserialises to null through Gson's Unsafe
            // path even though the Kotlin type is non-null.
            val currentMaintenance: PanoConfig.Companion.MaintenanceConfig? = configManager.config.maintenance
            val maintenanceConfig = currentMaintenance ?: PanoConfig.Companion.MaintenanceConfig().also {
                configManager.config.maintenance = it
            }

            val wasEnabled = maintenanceConfig.enabled
            val nowEnabled = maintenance.getBoolean("enabled")

            // Taking the public site down — or putting it back up — is re-authenticated like the
            // other critical settings. The rest of the card saves without a password.
            if (nowEnabled != wasEnabled) {
                authProvider.requirePassword(password, context)
            }

            maintenanceConfig.enabled = nowEnabled
            maintenanceConfig.bypassPermissionNode = bypassPermissionNode
            maintenanceConfig.showLoginButton = maintenance.getBoolean("showLoginButton")
            maintenanceConfig.customLoginUrl = customLoginUrl
            maintenanceConfig.showSiteLogo = maintenance.getBoolean("showSiteLogo")
            maintenanceConfig.title = maintenance.getString("title", "").take(200)
            maintenanceConfig.messageHtml = maintenance.getString("messageHtml", "")
            maintenanceConfig.customCss = maintenance.getString("customCss", "").take(64 * 1024)

            if (nowEnabled != wasEnabled) {
                maintenanceToggledTo = nowEnabled
            }
        }

        if (updatePeriod != null || releaseChannel != null || websiteName != null || websiteDescription != null || keywords != null || email != null || developmentMode != null || locale != null || allowUserLocaleSelection != null || httpPort != null || httpsPort != null || sslMode != null || sslCert != null || sslKey != null || redirectHttps != null || requireEmailVerification != null || passwordHashAlgorithm != null || maintenance != null) {
            configManager.saveConfig()
        }

        if (maintenance != null) {
            // Maintenance mode is applied live: the page is recomposed from the saved settings and
            // the 30 s bypass cache is dropped so the new rules take effect on the next request.
            // Deliberately no platformStateManager.restartRequired.
            maintenanceModeManager.composeAndSavePage()
            maintenanceModeManager.invalidateAccessCache()

            if (maintenanceToggledTo != null) {
                val authUserId = authProvider.getUserIdFromRoutingContext(context)
                val authUsername = databaseManager.userDao.getUsernameFromUserId(authUserId, sqlClient)!!

                databaseManager.panelActivityLogDao.add(
                    MaintenanceModeToggledLog(authUserId, authUsername, maintenanceToggledTo),
                    sqlClient
                )
            }
        }

        return Successful()
    }
}