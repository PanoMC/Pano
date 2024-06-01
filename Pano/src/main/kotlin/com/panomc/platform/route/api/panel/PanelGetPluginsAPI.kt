package com.panomc.platform.route.api.panel

import com.panomc.platform.PanoPluginDescriptor
import com.panomc.platform.PanoPluginWrapper
import com.panomc.platform.PluginManager
import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.model.*
import com.panomc.platform.util.AddonHashStatus
import com.panomc.platform.util.AddonStatusType
import com.panomc.platform.util.TextUtil
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Parameters.optionalParam
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaParser
import io.vertx.json.schema.common.dsl.Schemas.arraySchema
import io.vertx.json.schema.common.dsl.Schemas.enumSchema
import org.pf4j.PluginState

@Endpoint
class PanelGetPluginsAPI(
    private val databaseManager: DatabaseManager,
    private val pluginManager: PluginManager
) : PanelApi() {
    override val paths = listOf(Path("/api/panel/plugins", RouteType.GET))

    override fun getValidationHandler(schemaParser: SchemaParser): ValidationHandler =
        ValidationHandlerBuilder.create(schemaParser)
            .queryParameter(
                optionalParam(
                    "status", arraySchema().items(enumSchema(*AddonStatusType.entries.map { it.name }.toTypedArray()))
                )
            )
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        val parameters = getParameters(context)

        val statusType = AddonStatusType.valueOf(
            parameters.queryParameter("status")?.jsonArray?.first() as String? ?: AddonStatusType.ALL.name
        )

        val plugins = when (statusType) {
            AddonStatusType.ACTIVE -> pluginManager.plugins.filter { it.pluginState == PluginState.STARTED }
            AddonStatusType.DISABLED -> pluginManager.plugins.filter { it.pluginState != PluginState.STARTED }
            else -> pluginManager.plugins
        }.map { it as PanoPluginWrapper }

        val hashList = plugins.map { it.hash }

        val sqlClient = getSqlClient()

        val addonHashes = databaseManager.addonHashDao.byListOfHash(hashList, sqlClient)

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
                    "dependents" to plugins.filter { it.descriptor.dependencies.any { it.pluginId == plugin.pluginId } }
                        .map { it.pluginId },
                    "license" to panoPluginDescriptor.license,
                    "error" to if (plugin.failedException == null) null else TextUtil.getStackTraceAsString(plugin.failedException),
                    "hash" to plugin.hash,
                    "verifyStatus" to if (addonHashes[plugin.hash] == null) AddonHashStatus.UNKNOWN else addonHashes[plugin.hash]!!.status,
                    "sourceUrl" to panoPluginDescriptor.sourceUrl
                )
            }
        )

        return Successful(result)
    }
}