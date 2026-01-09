package com.panomc.platform.route.api.panel.plugins


import com.panomc.platform.PluginManager
import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.api.PanoPlugin
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.panel.log.DeletedPluginLog
import com.panomc.platform.auth.panel.permission.ManageAddonsPermission
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.error.NotFound
import com.panomc.platform.model.*
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Parameters.param
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.stringSchema
import org.pf4j.PluginState

@Endpoint
class PanelDeletePluginAPI(
    private val authProvider: AuthProvider,
    private val pluginManager: PluginManager,
    private val databaseManager: DatabaseManager
) : PanelApi() {
    override val paths = listOf(Path("/api/panel/plugins/:pluginId", RouteType.DELETE))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .pathParameter(param("pluginId", stringSchema()))
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        authProvider.requirePermission(ManageAddonsPermission(), context)

        val parameters = getParameters(context)

        val pluginId = parameters.pathParameter("pluginId").string

        val pluginWrapper = pluginManager.getPluginWrappers().firstOrNull { it.pluginId == pluginId }

        if (pluginWrapper == null) {
            throw NotFound()
        }

        val pluginFile = pluginWrapper.pluginPath.toFile()
        val plugin = (pluginWrapper.plugin as PanoPlugin)

        plugin.onUninstall()
        PluginManager.lifecycleListeners.forEach { it.onPluginUnload(plugin) }

        pluginManager.stopPlugin(pluginId)

        val dependents =
            pluginManager.plugins.filter { it.pluginState != PluginState.DISABLED && it.descriptor.dependencies.any { it.pluginId == pluginId && !it.isOptional } }
                .map { it.pluginId }

        dependents.forEach {
            pluginManager.disablePlugin(it)
        }

        pluginManager.unloadPlugin(pluginId)

        pluginFile.delete()

        val sqlClient = databaseManager.getSqlClient()
        val userId = authProvider.getUserIdFromRoutingContext(context)
        val username = databaseManager.userDao.getUsernameFromUserId(userId, sqlClient)!!

        databaseManager.panelActivityLogDao.add(
            DeletedPluginLog(
                userId,
                username,
                pluginId,
            ), sqlClient
        )

        return Successful()
    }
}