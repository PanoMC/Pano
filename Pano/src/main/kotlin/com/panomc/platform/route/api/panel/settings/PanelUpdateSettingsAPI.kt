package com.panomc.platform.route.api.panel.settings

import com.panomc.platform.PlatformStateManager
import com.panomc.platform.ReleaseStage
import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.panel.permission.ManagePlatformSettingsPermission
import com.panomc.platform.config.ConfigManager
import com.panomc.platform.config.PanoConfig
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.error.*
import com.panomc.platform.model.*
import com.panomc.platform.util.FileUploadUtil
import com.panomc.platform.util.HashUtil.hash
import com.panomc.platform.util.UpdatePeriod
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
    private val platformStateManager: PlatformStateManager
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
                    "image/jpeg"
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
                        .optionalProperty("password", stringSchema())
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

        if (updatePeriod != null) {
            configManager.config.updatePeriod = updatePeriod
        }

        if (releaseChannel != null) {
            configManager.config.releaseChannel = releaseChannel
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

        if (websiteUrl != null && websiteUrl != configManager.config.websiteUrl) {
            authProvider.requirePassword(password, context)
            configManager.config.websiteUrl = websiteUrl
            platformStateManager.restartRequired = true
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

        if (updatePeriod != null || releaseChannel != null || websiteName != null || websiteDescription != null || keywords != null || email != null || developmentMode != null || locale != null || allowUserLocaleSelection != null || httpPort != null || httpsPort != null || sslMode != null || sslCert != null || sslKey != null || redirectHttps != null) {
            configManager.saveConfig()
        }

        return Successful()
    }
}