package com.panomc.platform.route.api.panel.settings

import com.panomc.platform.ErrorCode
import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.config.ConfigManager
import com.panomc.platform.model.*
import com.panomc.platform.util.FileUploadUtil
import com.panomc.platform.util.UpdatePeriod
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Bodies
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaParser
import io.vertx.json.schema.common.dsl.Schemas.*
import java.io.File

@Endpoint
class PanelUpdateSettingAPI(
    private val configManager: ConfigManager
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
                contentTypeError = ErrorCode.FAVICON_WRONG_CONTENT_TYPE,
                fileSizeError = ErrorCode.FAVICON_EXCEEDS_SIZE,
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
                contentTypeError = ErrorCode.WEBSITE_LOGO_WRONG_CONTENT_TYPE,
                fileSizeError = ErrorCode.WEBSITE_LOGO_EXCEEDS_SIZE,
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
                            enumSchema(*UpdatePeriod.values().map { it.period }.toTypedArray())
                        )
                        .optionalProperty("locale", stringSchema())
                        .optionalProperty("websiteName", stringSchema())
                        .optionalProperty("websiteDescription", stringSchema())
                        .optionalProperty("supportEmail", stringSchema())
                        .optionalProperty("serverIpAddress", stringSchema())
                        .optionalProperty("serverGameVersion", stringSchema())
                        .optionalProperty("keywords", arraySchema().items(stringSchema()))
                )
            )
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        val parameters = getParameters(context)
        val data = parameters.body().jsonObject

        val fileUploads = context.fileUploads()

        val updatePeriod = UpdatePeriod.valueOf(period = data.getString("updatePeriod"))
        val locale = data.getString("locale")
        val websiteName = data.getString("websiteName")
        val websiteDescription = data.getString("websiteDescription")
        val supportEmail = data.getString("supportEmail")
        val serverIpAddress = data.getString("serverIpAddress")
        val serverGameVersion = data.getString("serverGameVersion")
        val keywords = data.getJsonArray("keywords")

        if (fileUploads.size > 0) {
            val savedFiles = FileUploadUtil.saveFiles(fileUploads, acceptedFileFields, configManager)

            savedFiles.forEach { savedFile ->
                val filePathsInConfig = configManager.getConfig().getJsonObject("file-paths")

                if (filePathsInConfig.getString(savedFile.field.name) != savedFile.path) {
                    val oldFile = File(
                        configManager.getConfig()
                            .getString("file-uploads-folder") + File.separator + filePathsInConfig.getString(
                            savedFile.field.name
                        )
                    )

                    if (oldFile.exists()) {
                        oldFile.delete()
                    }
                }

                filePathsInConfig.put(savedFile.field.name, savedFile.path)
            }
        }

        if (updatePeriod != null) {
            configManager.getConfig().put("update-period", updatePeriod.period)
        }

        if (locale != null) {
            configManager.getConfig().put("locale", locale)
        }

        if (websiteName != null) {
            configManager.getConfig().put("website-name", websiteName)
        }

        if (websiteDescription != null) {
            configManager.getConfig().put("website-description", websiteDescription)
        }

        if (supportEmail != null) {
            configManager.getConfig().put("support-email", supportEmail)
        }

        if (serverIpAddress != null) {
            configManager.getConfig().put("server-ip-address", serverIpAddress)
        }

        if (serverGameVersion != null) {
            configManager.getConfig().put("server-game-version", serverGameVersion)
        }

        if (keywords != null) {
            configManager.getConfig().put("keywords", keywords)
        }

        if (updatePeriod != null || websiteName != null || websiteDescription != null || keywords != null) {
            configManager.saveConfig()
        }

        return Successful()
    }
}