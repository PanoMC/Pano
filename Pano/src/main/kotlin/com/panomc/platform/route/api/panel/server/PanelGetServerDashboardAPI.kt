package com.panomc.platform.route.api.panel.server

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.panel.permission.ManageServerBackupsPermission
import com.panomc.platform.auth.panel.permission.ManageServersPermission
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.Server
import com.panomc.platform.error.NotExists
import com.panomc.platform.model.*
import com.panomc.platform.node.ManagedPluginJarResolver
import com.panomc.platform.node.NodeManager
import com.panomc.platform.node.dto.ScannedPluginData
import com.panomc.platform.node.message.PluginScanMessage
import com.panomc.platform.server.ServerManager
import com.panomc.platform.server.backup.ServerBackupStatus
import com.panomc.platform.server.feature.ServerFeatureResolver
import com.panomc.platform.server.plugins.PanoPluginStatus
import com.panomc.platform.server.plugins.PanoPluginUpdateService
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Parameters.param
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas
import com.panomc.platform.util.UsageMode

@Endpoint
class PanelGetServerDashboardAPI(
    private val authProvider: AuthProvider,
    private val databaseManager: DatabaseManager,
    private val serverFeatureResolver: ServerFeatureResolver,
    private val serverManager: ServerManager,
    private val nodeManager: NodeManager,
    private val managedPluginJarResolver: ManagedPluginJarResolver,
    private val panoPluginUpdateService: PanoPluginUpdateService
) : PanelApi() {
    override val usageModes = UsageMode.WITH_SERVERS

    override val paths = listOf(Path("/api/panel/servers/:id/dashboard", RouteType.GET))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .pathParameter(param("id", Schemas.numberSchema()))
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        authProvider.requirePermission(ManageServersPermission(), context)

        val parameters = getParameters(context)
        val id = parameters.pathParameter("id").long

        val result = mutableMapOf<String, Any?>(
            "server" to null,
            "connectedServerCount" to 0
        )

        val sqlClient = getSqlClient()

        val server = databaseManager.serverDao.getById(id, sqlClient) ?: throw NotExists()

        result["server"] = serverFeatureResolver.toPublicJsonObject(server)

        result["connectedServerCount"] = databaseManager.serverDao.countOfPermissionGranted(sqlClient)

        // The Statistics table's plugin rows (§2.4.26). Neither may slow the Overview down: the
        // latest version comes from a cache that is refreshed in the background, and the node is only
        // asked for its scan when the plugin cannot say, with a short deadline.
        result["panoPlugin"] = PanoPluginStatus(
            version = server.pluginVersion,
            latestVersion = managedPluginJarResolver.latestVersionOrWarm(server.type)
        ).toJsonObject()
            // Which route `POST .../pano-plugin/update` would take right now (`node` or `plugin`), or
            // null when neither can, so the badge's button is only offered when it can work.
            .put("updateMode", panoPluginUpdateService.modeFor(server)?.wire)
            // With no route, whether the admin can still do it by hand from the served jar.
            .put("updateManual", panoPluginUpdateService.canUpdateByHand(server))

        result["pluginCount"] = pluginCount(server)

        // The Statistics card's "Last backup" row: the newest finished one, for whoever may open
        // the backups page at all. Absent otherwise, and absent while there is none — the row
        // reads "—" either way.
        if (authProvider.hasPermission(ManageServerBackupsPermission(), context, id)) {
            result["lastBackup"] = databaseManager.serverBackupDao.getAllByServerId(id, sqlClient)
                .firstOrNull { it.status == ServerBackupStatus.READY }
                ?.let { backup ->
                    mapOf(
                        "id" to backup.uuid,
                        "name" to backup.name,
                        "createdAt" to backup.createdAt,
                        "mode" to backup.mode.name
                    )
                }
        }

        return Successful(result)
    }

    /**
     * How many plugins this server has: the ones its Pano plugin reports loaded when it is connected,
     * else the enabled jars the node scans in its directory when the node is online, else null.
     *
     * Enabled jars only, so the node's figure means what the plugin's does; a scan that fails or
     * takes longer than [SCAN_TIMEOUT_MS] is simply no answer.
     */
    private suspend fun pluginCount(server: Server): Int? {
        if (serverManager.isConnected(server.id)) {
            serverManager.getInstalledPlugins(server.id)?.let { return it.size }
        }

        val nodeId = server.nodeId ?: return null
        val uuid = server.uuid ?: return null

        if (!server.isManaged || !nodeManager.isConnected(nodeId)) {
            return null
        }

        return try {
            val payload = nodeManager.request(nodeId, PluginScanMessage(uuid), SCAN_TIMEOUT_MS)

            if (!payload.getBoolean("ok", false)) {
                null
            } else {
                ScannedPluginData.listFrom(payload).count { it.enabled }
            }
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        /** How long the Overview waits for a node's plugin scan before showing a dash instead. */
        private const val SCAN_TIMEOUT_MS = 2_000L
    }
}