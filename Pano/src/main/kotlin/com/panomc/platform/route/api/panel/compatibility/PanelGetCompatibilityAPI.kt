package com.panomc.platform.route.api.panel.compatibility

import com.panomc.platform.config.ConfigManager
import com.panomc.platform.PluginManager
import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.api.ExternalUrlProvider
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.panel.permission.ManageAddonsPermission
import com.panomc.platform.auth.panel.permission.ManageViewPermission
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.error.NoPermission
import com.panomc.platform.gate.CompatibilityReconciler
import com.panomc.platform.model.*
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.json.schema.SchemaRepository

/**
 * `GET /api/v1/panel/compatibility` -- the one compatibility surface of the panel (doc 04 section 7): the API level
 * range of this Pano, the plugins and themes the gate refused (with what the last reconcile did about each), the game
 * servers and nodes still on an old protocol, and the addresses plugins registered outside Pano that moved.
 * Computed on every call; nothing is stored. Open to the holders of the manage-addons or the manage-view permission.
 */
@Endpoint
class PanelGetCompatibilityAPI(
    private val authProvider: AuthProvider,
    private val reconciler: CompatibilityReconciler,
    private val databaseManager: DatabaseManager,
    private val pluginManager: PluginManager,
    private val configManager: ConfigManager
) : PanelApi() {
    override val paths = listOf(Path("/compatibility", RouteType.GET))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler? = null

    override suspend fun handle(context: RoutingContext): Result {
        requireCompatibilityAccess(authProvider, context)

        return Successful(payload(databaseManager, pluginManager, reconciler, CompatibilityPayload.localNodeManaged(configManager.config)))
    }

    companion object {
        suspend fun requireCompatibilityAccess(authProvider: AuthProvider, context: RoutingContext) {
            if (!authProvider.hasPermission(ManageAddonsPermission(), context) &&
                !authProvider.hasPermission(ManageViewPermission(), context)
            ) {
                throw NoPermission()
            }
        }

        suspend fun payload(
            databaseManager: DatabaseManager,
            pluginManager: PluginManager,
            reconciler: CompatibilityReconciler,
            localNodeManaged: Boolean = true
        ): Map<String, Any?> {
            val sqlClient = databaseManager.getSqlClient()

            val servers = databaseManager.serverDao.getAllByPermissionGranted(sqlClient)
            val nodes = databaseManager.nodeDao.getAll(sqlClient)

            val providers = pluginManager.getActivePanoPlugins().associate { plugin ->
                plugin.pluginId to plugin.pluginBeanContext.getBeansOfType(ExternalUrlProvider::class.java).values.toList()
            }

            return CompatibilityPayload.build(reconciler, servers, nodes, providers, localNodeManaged)
        }
    }
}
