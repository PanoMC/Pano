package com.panomc.platform.route.api.panel.plugins

import com.panomc.platform.PanoPluginDescriptor
import com.panomc.platform.PanoPluginWrapper
import com.panomc.platform.PluginManager
import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.panel.permission.ManageAddonsPermission
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.error.NotFound
import com.panomc.platform.model.*
import com.panomc.platform.util.ResourceHashStatus
import com.panomc.platform.util.TextUtil
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Parameters.param
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.stringSchema
import org.pf4j.PluginState

@Endpoint
class PanelGetPluginAPI(
    private val databaseManager: DatabaseManager,
    private val pluginManager: PluginManager,
    private val authProvider: AuthProvider
) : PanelApi() {
    override val paths = listOf(Path("/api/panel/plugins/:pluginId", RouteType.GET))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .pathParameter(param("pluginId", stringSchema()))
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        authProvider.requirePermission(ManageAddonsPermission(), context)

        val parameters = getParameters(context)

        val pluginId = parameters.pathParameter("pluginId").string

        val plugins = pluginManager.plugins
        val plugin = plugins.find { it.pluginId == pluginId } as PanoPluginWrapper? ?: throw NotFound()
        val panoPluginDescriptor = plugin.descriptor as PanoPluginDescriptor

        val sqlClient = getSqlClient()

        val resourceHashes = databaseManager.resourceHashDao.byListOfHash(listOf(plugin.hash), sqlClient)

        return Successful(
            mapOf(
                "data" to mapOf(
                    "id" to plugin.pluginId,
                    "author" to panoPluginDescriptor.provider,
                    "description" to panoPluginDescriptor.pluginDescription,
                    "version" to panoPluginDescriptor.version,
                    "status" to plugin.pluginState,
                    "dependencies" to panoPluginDescriptor.dependencies,
                    "notStartedDependencies" to panoPluginDescriptor.dependencies.filter { dependency -> !dependency.isOptional && plugins.any { it.pluginId == dependency.pluginId && it.pluginState != PluginState.STARTED } }
                        .map { it.pluginId },
                    "dependents" to plugins.filter { it.pluginState == PluginState.STARTED && it.descriptor.dependencies.any { it.pluginId == plugin.pluginId && !it.isOptional } }
                        .map { it.pluginId },
                    "license" to panoPluginDescriptor.license,
                    "error" to if (plugin.failedException == null) null else TextUtil.getStackTraceAsString(plugin.failedException),
                    "hash" to plugin.hash,
                    "verifyStatus" to if (resourceHashes[plugin.hash] == null) ResourceHashStatus.UNKNOWN else resourceHashes[plugin.hash]!!.status,
                    "sourceUrl" to panoPluginDescriptor.sourceUrl
                )
            )
        )
    }
}