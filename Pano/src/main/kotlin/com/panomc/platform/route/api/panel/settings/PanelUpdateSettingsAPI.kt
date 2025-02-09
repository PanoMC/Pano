package com.panomc.platform.route.api.panel.settings

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.panel.permission.ManagePlatformSettingsPermission
import com.panomc.platform.config.ConfigManager
import com.panomc.platform.error.FaviconExceedsSize
import com.panomc.platform.error.FaviconWrongContentType
import com.panomc.platform.error.WebsiteLogoExceedsSize
import com.panomc.platform.error.WebsiteLogoWrongContentType
import com.panomc.platform.model.*
import com.panomc.platform.util.FileUploadUtil
import com.panomc.platform.util.UpdatePeriod
import io.vertx.ext.mail.StartTLSOptions
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.RequestPredicate
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Bodies
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaParser
import io.vertx.json.schema.common.dsl.Schemas.*
import java.io.File

@Endpoint
class PanelUpdateSettingsAPI(
    private val configManager: ConfigManager,
    private val authProvider: AuthProvider
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
                    "image/svg+xml",
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
                    "image/svg+xml",
                ),
                contentTypeError = WebsiteLogoWrongContentType(),
                fileSizeError = WebsiteLogoExceedsSize(),
                withTempName = false,
                size = 2 * 1024 * 1024 // 2 MB
            )
        )
    )

    override fun getValidationHandler(schemaParser: SchemaParser): ValidationHandler =
        ValidationHandlerBuilder.create(schemaParser)
            .body(
                Bodies.multipartFormData(
                    objectSchema()
                        .optionalProperty(
                            "updatePeriod",
                            enumSchema(*UpdatePeriod.entries.map { it.name }.toTypedArray())
                        )
                        .optionalProperty("locale", stringSchema())
                        .optionalProperty("websiteName", stringSchema())
                        .optionalProperty("websiteDescription", stringSchema())
                        .optionalProperty("supportEmail", stringSchema())
                        .optionalProperty("serverIpAddress", stringSchema())
                        .optionalProperty("serverGameVersion", stringSchema())
                        .optionalProperty("keywords", arraySchema().items(stringSchema()))
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
        val locale = data.getString("locale")
        val websiteName = data.getString("websiteName")
        val websiteDescription = data.getString("websiteDescription")
        val supportEmail = data.getString("supportEmail")
        val serverIpAddress = data.getString("serverIpAddress")
        val serverGameVersion = data.getString("serverGameVersion")
        val keywords = data.getJsonArray("keywords")

        val email = data.getJsonObject("email")

        if (fileUploads.isNotEmpty()) {
            val savedFiles = FileUploadUtil.saveFiles(fileUploads, acceptedFileFields, configManager)

            savedFiles.forEach { savedFile ->
                val filePathsInConfig = configManager.config.filePaths

                if (filePathsInConfig[savedFile.field.name] != savedFile.path) {
                    val oldFile = File(
                        configManager.config
                            .fileUploadsFolder + File.separator + filePathsInConfig[savedFile.field.name]
                    )

                    if (oldFile.exists()) {
                        oldFile.delete()
                    }
                }

                filePathsInConfig[savedFile.field.name] = savedFile.path
            }
        }

        if (updatePeriod != null) {
            configManager.config.updatePeriod = updatePeriod
        }

        if (locale != null) {
            configManager.config.locale = locale
        }

        if (websiteName != null) {
            configManager.config.websiteName = websiteName
        }

        if (websiteDescription != null) {
            configManager.config.websiteDescription = websiteDescription
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
            configManager.config.keywords = keywords.toList() as List<String>
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

        if (updatePeriod != null || websiteName != null || websiteDescription != null || keywords != null || email != null) {
            configManager.saveConfig()
        }

        return Successful()
    }
}