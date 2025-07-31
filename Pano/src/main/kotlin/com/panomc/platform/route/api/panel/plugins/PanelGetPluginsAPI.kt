package com.panomc.platform.route.api.panel.plugins

import com.panomc.platform.PanoPluginDescriptor
import com.panomc.platform.PanoPluginWrapper
import com.panomc.platform.PluginManager
import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.model.*
import com.panomc.platform.util.ResourceHashStatus
import com.panomc.platform.util.ResourceStatusType
import com.panomc.platform.util.TextUtil
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Parameters
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas
import org.pf4j.PluginState

@Endpoint
class PanelGetPluginsAPI(
    private val databaseManager: DatabaseManager,
    private val pluginManager: PluginManager
) : PanelApi() {
    override val paths = listOf(Path("/api/panel/plugins", RouteType.GET))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .queryParameter(
                Parameters.optionalParam(
                    "status",
                    Schemas.arraySchema()
                        .items(Schemas.enumSchema(*ResourceStatusType.entries.map { it.name }.toTypedArray()))
                )
            )
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        val parameters = getParameters(context)

        val statusType = ResourceStatusType.valueOf(
            parameters.queryParameter("status")?.jsonArray?.first() as String? ?: ResourceStatusType.ALL.name
        )

        val plugins = when (statusType) {
            ResourceStatusType.ACTIVE -> pluginManager.plugins.filter { it.pluginState == PluginState.STARTED }
            ResourceStatusType.DISABLED -> pluginManager.plugins.filter { it.pluginState != PluginState.STARTED }
            else -> pluginManager.plugins
        }.map { it as PanoPluginWrapper }

        val hashList = plugins.map { it.hash }

        val sqlClient = getSqlClient()

        val addonHashes = databaseManager.resourceHashDao.byListOfHash(hashList, sqlClient)

        val result = mutableMapOf(
            "plugins" to plugins.map { plugin ->
                val panoPluginDescriptor = plugin.descriptor as PanoPluginDescriptor

                mapOf(
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
                    "verifyStatus" to if (addonHashes[plugin.hash] == null) ResourceHashStatus.UNKNOWN else addonHashes[plugin.hash]!!.status,
                    "sourceUrl" to panoPluginDescriptor.sourceUrl
                )
            }
        )

        return Successful(result)
    }
}