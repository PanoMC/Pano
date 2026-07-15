package com.panomc.platform.route.api.plugins

import com.panomc.platform.Main
import com.panomc.platform.PluginManager
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
import io.vertx.kotlin.coroutines.coAwait
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.Logger
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import io.vertx.core.json.JsonArray

@Endpoint
class GetPluginUiZipAPI(
    private val configManager: ConfigManager,
    private val pluginUiManager: PluginUiManager,
    private val pluginManager: PluginManager,
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
            pluginUiManager.getActiveRegisteredPlugins(pluginManager).firstOrNull { it.first.pluginId == pluginId }

        val plugin = pluginIdWithPlugin?.first

        if (plugin == null) {
            throw NotFound()
        }

        val pluginUiZipFileName = "plugin-ui.zip"
        val response = context.response()
        val mimeType = MimeTypeUtil.getMimeTypeFromFileName(pluginUiZipFileName)

        // Use the same mode source as plugin UI registration (PluginUiManager) so a plugin
        // registered with the dev "dev-build" hash is also served via the dev re-zip path,
        // instead of falling through to a NotFound after headers were already written.
        if (Main.ENVIRONMENT == Main.Companion.EnvironmentType.DEVELOPMENT) {
            val uiResourcesDir = PluginDevUtil.getPluginResourceDir(pluginId, "plugin-ui")

            if (uiResourcesDir != null) {
                val zipBytes = buildDevZip(context, pluginId, uiResourcesDir)

                response.putHeader("Content-Type", mimeType)
                response.putHeader("Content-Length", zipBytes.size.toString())
                response.end(io.vertx.core.buffer.Buffer.buffer(zipBytes))
                return null
            }
        }

        // Resolve the resource before touching the response so the NotFound error path starts
        // from a clean response (no half-set Content-Type / chunked body).
        val resource = plugin.getResource(pluginUiZipFileName) ?: throw NotFound()

        response.putHeader("Content-Type", mimeType)
        response.isChunked = true

        resource.writeToResponse(context.vertx(), response)

        response.end()

        return null
    }

    /**
     * Builds the dev-mode plugin-ui zip off the event loop, with a per-pluginId single-flight so
     * concurrent requests for the same plugin share one build instead of racing the live `bun dev`
     * writer (which produced corrupt/partial zips) and stalling the event loop.
     */
    private suspend fun buildDevZip(context: RoutingContext, pluginId: String, uiResourcesDir: File): ByteArray {
        val deferred = devZipMutex.withLock {
            inFlightDevZips.getOrPut(pluginId) {
                context.vertx().executeBlocking<ByteArray> {
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

                    zipBytes
                }
            }
        }

        try {
            return deferred.coAwait()
        } finally {
            devZipMutex.withLock {
                inFlightDevZips.remove(pluginId, deferred)
            }
        }
    }

    companion object {
        private val inFlightDevZips = ConcurrentHashMap<String, io.vertx.core.Future<ByteArray>>()
        private val devZipMutex = Mutex()
    }
}