package com.panomc.platform.route.api.panel.server.plugins

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.panel.log.ServerPluginToggledLog
import com.panomc.platform.auth.panel.permission.ManageServerPluginsPermission
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.error.BadRequest
import com.panomc.platform.error.NotExists
import com.panomc.platform.error.ServerOffline
import com.panomc.platform.model.*
import com.panomc.platform.node.ManagedServerFileClient
import com.panomc.platform.node.dto.ScannedPluginData
import com.panomc.platform.node.message.PluginScanMessage
import com.panomc.platform.node.message.PluginToggleMessage
import com.panomc.platform.server.ServerManager
import com.panomc.platform.server.feature.ServerFeature
import com.panomc.platform.server.feature.ServerFeatureResolver
import com.panomc.platform.server.feature.ServerFeatureSource
import com.panomc.platform.server.message.SetPluginEnabledMessage
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.RequestPredicate
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Bodies.json
import io.vertx.ext.web.validation.builder.Parameters.param
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.booleanSchema
import io.vertx.json.schema.common.dsl.Schemas.numberSchema
import io.vertx.json.schema.common.dsl.Schemas.objectSchema
import io.vertx.json.schema.common.dsl.Schemas.stringSchema
import com.panomc.platform.util.UsageMode

/**
 * Enables or disables one plugin, through whichever source can do it (SM-52, §2.4.17).
 *
 * Two genuinely different operations behind one switch. A Bukkit server's own plugin manager can
 * start and stop a plugin while the game runs, which is why the plugin path outranks the other
 * one; a node can only rename `x.jar` to `x.jar.disabled`, which changes nothing until the server
 * restarts — so that path answers `restartRequired` and the panel says so rather than showing a
 * switch that appears to have done something.
 *
 * `:name` is the plugin's name on the plugin path and its file name on the node path, because
 * those are the only identifiers each side actually holds; the node path accepts either and
 * matches it against the scan rather than putting anything from the request onto a disk.
 */
@Endpoint
class PanelSetServerPluginEnabledAPI(
    private val databaseManager: DatabaseManager,
    private val authProvider: AuthProvider,
    private val serverManager: ServerManager,
    private val fileClient: ManagedServerFileClient,
    private val serverFeatureResolver: ServerFeatureResolver
) : PanelApi() {
    override val usageModes = UsageMode.WITH_SERVERS

    override val paths = listOf(Path("/api/panel/servers/:id/plugins/:name/enabled", RouteType.PUT))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .pathParameter(param("id", numberSchema()))
            .pathParameter(param("name", stringSchema()))
            .body(
                json(
                    objectSchema()
                        .requiredProperty("enabled", booleanSchema())
                )
            )
            .predicate(RequestPredicate.BODY_REQUIRED)
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        val parameters = getParameters(context)
        val id = parameters.pathParameter("id").long

        authProvider.requirePermission(ManageServerPluginsPermission(), context, id)

        val name = parameters.pathParameter("name").string.trim()
        val enabled = parameters.body().jsonObject.getBoolean("enabled")

        val sqlClient = getSqlClient()

        val server = databaseManager.serverDao.getById(id, sqlClient) ?: throw NotExists()

        if (!server.permissionGranted) {
            throw NotExists()
        }

        val source = serverFeatureResolver.pick(server, ServerFeature.PLUGINS_TOGGLE)

        val userId = authProvider.getUserIdFromRoutingContext(context)
        val username = databaseManager.userDao.getUsernameFromUserId(userId, sqlClient) ?: throw NotExists()

        val result = when (source) {
            ServerFeatureSource.NODE -> toggleOnNode(id, name, enabled, sqlClient)
            else -> toggleInGame(id, name, enabled)
        }

        databaseManager.panelActivityLogDao.add(
            ServerPluginToggledLog(userId, username, id, result.first, enabled),
            sqlClient
        )

        return Successful(mapOf("name" to result.first, "restartRequired" to result.second))
    }

    /**
     * Asks the server's own plugin manager to do it, which takes effect immediately.
     *
     * The name is matched against the list the server itself reported, so the message can only
     * ever name a plugin that is genuinely installed there.
     */
    private fun toggleInGame(serverId: Long, name: String, enabled: Boolean): Pair<String, Boolean> {
        if (!serverManager.isConnected(serverId)) {
            throw ServerOffline()
        }

        val plugin = serverManager.getInstalledPlugins(serverId)
            ?.firstOrNull { it.name.equals(name, ignoreCase = true) }
            ?: throw NotExists()

        if (!serverManager.sendMessage(serverId, SetPluginEnabledMessage(plugin.name, enabled))) {
            throw ServerOffline()
        }

        return plugin.name to false
    }

    /** Renames the jar, which is all a node can do and never takes effect before a restart. */
    private suspend fun toggleOnNode(
        serverId: Long,
        name: String,
        enabled: Boolean,
        sqlClient: io.vertx.sqlclient.SqlClient
    ): Pair<String, Boolean> {
        val target = fileClient.resolve(serverId, sqlClient, ServerFeature.PLUGINS_TOGGLE)

        val scan = ScannedPluginData.listFrom(fileClient.request(target, PluginScanMessage(target.serverUuid)))

        val plugin = scan.firstOrNull { it.file.equals(name, ignoreCase = true) }
            ?: scan.firstOrNull { it.name.equals(name, ignoreCase = true) }
            ?: throw NotExists()

        if (plugin.enabled == enabled) {
            throw BadRequest()
        }

        val payload = fileClient.request(target, PluginToggleMessage(target.serverUuid, plugin.file, enabled))

        return plugin.name to payload.getBoolean("restartRequired", true)
    }
}
