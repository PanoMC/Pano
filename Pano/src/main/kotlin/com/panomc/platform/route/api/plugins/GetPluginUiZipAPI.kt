package com.panomc.platform.route.api.plugins

import com.panomc.platform.PluginUiManager
import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.config.ConfigManager
import com.panomc.platform.error.NotFound
import com.panomc.platform.model.Api
import com.panomc.platform.model.Path
import com.panomc.platform.model.Result
import com.panomc.platform.model.RouteType
import com.panomc.platform.util.FileResourceUtil.getResource
import com.panomc.platform.util.FileResourceUtil.writeToResponse
import com.panomc.platform.util.MimeTypeUtil
import com.panomc.platform.util.PluginDevUtil
import com.panomc.platform.util.ZipUtil
import com.panomc.platform.util.FileUtil
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Parameters.param
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.stringSchema
import io.vertx.kotlin.coroutines.dispatcher
import kotlinx.coroutines.withContext
import org.slf4j.Logger
import java.io.File
import io.vertx.core.json.JsonArray

@Endpoint
class GetPluginUiZipAPI(
    private val configManager: ConfigManager,
    private val pluginUiManager: PluginUiManager,
    private val logger: Logger
) : Api() {
    override val paths = listOf(Path("/api/plugins/:pluginId/resources/plugin-ui.zip", RouteType.GET))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .pathParameter(param("pluginId", stringSchema()))
            .build()

    override suspend fun handle(context: RoutingContext): Result? {
        val parameters = getParameters(context)

        val pluginId = parameters.pathParameter("pluginId").string

        val pluginIdWithPlugin =
            pluginUiManager.getRegisteredPlugins().toList().firstOrNull { it.first.pluginId == pluginId }

        val plugin = pluginIdWithPlugin?.first

        if (plugin == null) {
            throw NotFound()
        }

        val pluginUiZipFileName = "plugin-ui.zip"
        val response = context.response()
        val mimeType = MimeTypeUtil.getMimeTypeFromFileName(pluginUiZipFileName)

        response.putHeader("Content-Type", mimeType)
        response.isChunked = true

        val config = configManager.config
        if (config.developmentMode) {
            val uiResourcesDir = PluginDevUtil.getPluginResourceDir(pluginId, "plugin-ui")

            if (uiResourcesDir != null) {
                val filesToZip = mutableSetOf<String>()

                listOf("server", "client").forEach { subDir ->
                    val manifestFile = File(uiResourcesDir, "$subDir/manifest.json")
                    if (manifestFile.exists()) {
                        try {
                            val manifest = JsonArray(manifestFile.readText())
                            manifest.forEach { fileName ->
                                if (fileName is String) {
                                    filesToZip.add("$subDir/$fileName")
                                }
                            }
                        } catch (e: Exception) {
                            logger.error("Failed to read manifest for $pluginId in $subDir", e)
                        }
                    }
                }

                val zipBytes = if (filesToZip.isNotEmpty()) {
                    ZipUtil.zipFilesFromFolder(uiResourcesDir, filesToZip)
                } else {
                    ZipUtil.zipFoldersToBytes(mapOf("" to uiResourcesDir))
                }
                logger.info("Zipping UI for $pluginId (${FileUtil.formatSize(zipBytes.size.toLong())})")
                logger.debug("UI Source Directory for $pluginId: ${uiResourcesDir.absolutePath}")

                response.isChunked = false
                response.putHeader("Content-Length", zipBytes.size.toString())
                response.end(io.vertx.core.buffer.Buffer.buffer(zipBytes))
                return null
            }
        }

        val resource = plugin.getResource(pluginUiZipFileName) ?: throw NotFound()

        resource.writeToResponse(response)

        withContext(context.vertx().dispatcher()) {
            resource.close()
        }

        response.end()

        return null
    }
}