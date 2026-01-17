package com.panomc.platform.route.api.panel.plugins

import com.panomc.platform.PanoPluginDescriptor
import com.panomc.platform.PanoPluginWrapper
import com.panomc.platform.PluginManager
import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.panel.permission.ManageAddonsPermission
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.model.*
import com.panomc.platform.util.FileUtil.getSize
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
    private val pluginManager: PluginManager,
    private val authProvider: AuthProvider
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
            .queryParameter(Parameters.optionalParam("search", Schemas.stringSchema()))
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        authProvider.requirePermission(ManageAddonsPermission(), context)

        val parameters = getParameters(context)

        val statusType = ResourceStatusType.valueOf(
            parameters.queryParameter("status")?.jsonArray?.first() as String? ?: ResourceStatusType.ALL.name
        )

        val search = parameters.queryParameter("search")?.string

        val plugins = when (statusType) {
            ResourceStatusType.ACTIVE -> pluginManager.plugins.filter { it.pluginState == PluginState.STARTED }
            ResourceStatusType.DISABLED -> pluginManager.plugins.filter { it.pluginState != PluginState.STARTED }
            else -> pluginManager.plugins
        }.map { it as PanoPluginWrapper }.filter {
            if (search == null) return@filter true
            val descriptor = it.descriptor as PanoPluginDescriptor
            descriptor.name.contains(search, ignoreCase = true) || descriptor.description?.contains(
                search,
                ignoreCase = true
            ) == true
        }

        val hashList = plugins.map { it.hash }

        val sqlClient = getSqlClient()

        val resourceHashes = databaseManager.resourceHashDao.byListOfHash(hashList, sqlClient)

        return Successful(
            mapOf(
                "data" to plugins.map { plugin ->
                val panoPluginDescriptor = plugin.descriptor as PanoPluginDescriptor

                mapOf(
                    "id" to plugin.pluginId,
                    "name" to panoPluginDescriptor.name,
                    "description" to panoPluginDescriptor.description,
                    "panoVersion" to panoPluginDescriptor.panoVersion,
                    "developer" to panoPluginDescriptor.developer,
                    "version" to panoPluginDescriptor.version,
                    "license" to panoPluginDescriptor.license,
                    "sourceUrl" to panoPluginDescriptor.sourceUrl,
                    "status" to plugin.pluginState,
                    "hash" to plugin.hash,
                    "dependencies" to panoPluginDescriptor.dependencies,
                    "requires" to panoPluginDescriptor.requires,
                    "notStartedDependencies" to panoPluginDescriptor.dependencies.filter { dependency -> !dependency.isOptional && plugins.any { it.pluginId == dependency.pluginId && it.pluginState != PluginState.STARTED } }
                        .map { it.pluginId },
                    "dependents" to plugins.filter { it.pluginState == PluginState.STARTED && it.descriptor.dependencies.any { it.pluginId == plugin.pluginId && !it.isOptional } }
                        .map { it.pluginId },
                    "error" to if (plugin.failedException == null) null else TextUtil.getStackTraceAsString(plugin.failedException),
                    "verifyStatus" to if (resourceHashes[plugin.hash] == null) ResourceHashStatus.UNKNOWN else resourceHashes[plugin.hash]!!.status,
                    "size" to plugin.pluginPath.toFile().getSize()
                )
            }
            ))
    }
}