package com.panomc.platform.route.api.panel.server.plugins

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.panel.permission.ManageServerPluginsPermission
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.error.NotExists
import com.panomc.platform.error.ServerCapabilityMissing
import com.panomc.platform.error.NodeOffline
import com.panomc.platform.error.FeatureUnavailable
import com.panomc.platform.model.*
import com.panomc.platform.server.feature.ServerFeature
import com.panomc.platform.node.ManagedServerFileClient
import com.panomc.platform.node.NodeManager
import com.panomc.platform.node.dto.ScannedPluginData
import com.panomc.platform.node.message.PluginScanMessage
import com.panomc.platform.server.ServerCapability
import com.panomc.platform.server.ServerManager
import com.panomc.platform.server.feature.ServerFeatureInputs
import com.panomc.platform.server.feature.ServerFeatureResolver
import com.panomc.platform.server.feature.ServerFeatureSource
import com.panomc.platform.server.plugins.ManagedServerPluginService
import com.panomc.platform.server.plugins.PluginFileNaming
import com.panomc.platform.server.plugins.PluginIdentificationService
import com.panomc.platform.server.plugins.PluginLoaderMapping
import com.panomc.platform.server.plugins.PluginUpdateService
import com.panomc.platform.server.plugins.dto.ServerPluginFileData
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Parameters.param
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.numberSchema
import com.panomc.platform.util.UsageMode

/**
 * Lists the plugins or mods installed on a server.
 *
 * Two different truths, deliberately kept apart. `plugins` is what the running game loaded, which
 * only exists while the server is connected, so an offline server returns an empty list with
 * `online` false rather than a stale snapshot. `files` is what is actually in the plugin
 * directory right now, which only a node can see and only for a managed server.
 *
 * The gap between them is the useful part: a jar that is there but is not loaded is a plugin
 * installed since the last boot, which is what `restartRequired` reports. `toggleable` is separate
 * again and says whether the enable switch means anything: only the Bukkit family can start and
 * stop a plugin at runtime.
 *
 * Since SM-52 there is a third list. A server with no Pano plugin in it used to show nothing but
 * file names; now the node reads each jar's own descriptor (`PLUGIN_SCAN`) and the result is
 * merged onto the same file listing, so the page has names, versions and authors for a server
 * nothing is running inside. `source` says which of the two lists the panel should render, and it
 * is the resolver's decision rather than this endpoint's.
 */
@Endpoint
class PanelGetServerPluginsAPI(
    private val databaseManager: DatabaseManager,
    private val authProvider: AuthProvider,
    private val serverManager: ServerManager,
    private val nodeManager: NodeManager,
    private val fileClient: ManagedServerFileClient,
    private val pluginService: ManagedServerPluginService,
    private val pluginUpdateService: PluginUpdateService,
    private val identificationService: PluginIdentificationService,
    private val serverFeatureResolver: ServerFeatureResolver
) : PanelApi() {
    override val usageModes = UsageMode.WITH_SERVERS

    override val paths = listOf(Path("/api/panel/servers/:id/plugins", RouteType.GET))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .pathParameter(param("id", numberSchema()))
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        val parameters = getParameters(context)
        val id = parameters.pathParameter("id").long

        authProvider.requirePermission(ManageServerPluginsPermission(), context, id)

        val sqlClient = getSqlClient()

        val server = databaseManager.serverDao.getById(id, sqlClient) ?: throw NotExists()

        if (!server.permissionGranted) {
            throw NotExists()
        }

        // Before any round trip: a software with no plugin platform has no directory to read, and
        // asking the node for one is what used to come back as a 404 (§2.4.35).
        refuseUnsupported(serverFeatureResolver.inputsOf(server))

        val plugins = serverManager.getInstalledPlugins(id).orEmpty()
        val online = serverManager.isConnected(id)

        // The directory is read over whichever side serves *files* (§2.4.17: node first, then a
        // plugin with `files`), never over the side that serves the plugin list -- a connected
        // plugin without `files` would otherwise be asked for a listing it cannot give and the
        // page would wait out the timeout. No side at all reports an empty list, not an error.
        val target = try {
            fileClient.resolve(id, sqlClient, ServerFeature.FILES_SOURCE)
        } catch (_: FeatureUnavailable) {
            null
        } catch (_: NodeOffline) {
            null
        } catch (_: ServerCapabilityMissing) {
            null
        }

        // Null means the directory could not be read, which is not the same as an empty one and
        // must never be treated as "every tracked plugin is gone".
        val listing = target?.let { pluginService.listFilesOrNull(it, plugins) }

        val files: List<ServerPluginFileData> = listing.orEmpty()

        val filenames = listing?.map { it.filename }?.toSet()

        // Opening this page is the moment somebody wants to know what the unnamed jars are, and
        // the gate inside keeps it to once every six hours per server.
        if (target != null && filenames != null) {
            identificationService.identifyIfDue(target, filenames.toList(), sqlClient)
        }

        val features = serverFeatureResolver.resolve(server)

        // Only asked for when it is the list the panel will actually show: a scan walks every jar
        // in the directory, and doing that to throw it away behind the plugin's own list would be
        // a modded server's `mods/` folder read on every page load for nothing.
        val scanned = if (features.plugins.list == ServerFeatureSource.NODE && target != null) {
            scan(target, files)
        } else {
            emptyList()
        }

        // Rows exist for whichever side installed the jar -- a node or a plugin with
        // `plugin-install` -- so a linked server that installs through its plugin is tracked too.
        // A switched-off jar is still that plugin: where it came from is kept for when it is
        // switched back on, and only left out of what the page shows while it is off.
        val presentOrDisabled = filenames?.let { names -> names + names.map { PluginFileNaming.enabledNameOf(it) } }

        val rows = pluginUpdateService.forgetMissing(
            id,
            databaseManager.serverPluginInstallDao.getByServerId(id, sqlClient),
            presentOrDisabled,
            sqlClient
        ).filterNot { row ->
            filenames != null && row.filename !in filenames && (row.filename + PluginFileNaming.DISABLED_SUFFIX) in filenames
        }

        val tracked = pluginUpdateService.tracked(
            server,
            rows,
            deadlineAt = System.currentTimeMillis() + PluginUpdateService.LIST_BUDGET_MS
        )

        val trackedNames = tracked.map { it.filename }.toSet()

        return Successful(
            mapOf(
                "plugins" to plugins.map { it.toJsonObject() },
                "files" to JsonArray(files.map { it.toJsonObject() }),
                "restartRequired" to pluginService.restartRequired(files, online),
                "targetDir" to PluginLoaderMapping.targetDir(server.type),
                "online" to online,
                "capable" to server.hasCapability(ServerCapability.PLUGINS),
                "toggleable" to server.type.isBukkitFamily,
                "managed" to server.isManaged,
                "tracked" to JsonArray(tracked.map { it.toJsonObject() }),
                "updateCount" to tracked.count { it.updateAvailable },
                // Whoever serves the files can hash them: a node on protocol 2, or a plugin with
                // `plugin-install`; no side at all is "not now", not "never".
                "identifySupported" to (target?.let { identificationService.isSupported(it) } ?: false),
                "scanned" to JsonArray(scanned),
                "source" to features.plugins.list?.id,
                "toggleSource" to features.plugins.toggle?.id,
                "installSource" to features.plugins.install?.id,
                "unknownFiles" to JsonArray(
                    files.map { it.filename }
                        // Identification only ever hashes jars that are switched on.
                        .filter { PluginFileNaming.isJarName(it) }
                        .filterNot { it in trackedNames }
                        .filterNot { PluginFileNaming.isPanoPluginJar(it) }
                )
            )
        )
    }

    /**
     * What the node reads out of the jars, with the file listing merged back onto it.
     *
     * Two halves of one row: the descriptor says what the jar *is*, the listing says how big it is
     * and when it was last touched, and the page needs both. A scan that fails is an empty list
     * and not an error — the file names are still there, which is exactly what this page showed
     * before the scan existed.
     */
    private suspend fun scan(
        target: ManagedServerFileClient.Target,
        files: List<ServerPluginFileData>
    ): List<JsonObject> {
        // Pano's own errors are Throwables rather than Exceptions, so a refusal from the node
        // needs its own catch to stay "no scan" instead of failing the page.
        val payload = try {
            fileClient.request(target, PluginScanMessage(target.serverUuid))
        } catch (_: Exception) {
            return emptyList()
        } catch (_: com.panomc.platform.model.Error) {
            return emptyList()
        }

        val byName = files.associateBy { it.filename }

        return ScannedPluginData.listFrom(payload).map { plugin ->
            val file = byName[plugin.file]

            plugin.toJsonObject()
                .put("size", file?.size)
                .put("modified", file?.modified)
        }
    }

    companion object {
        /**
         * Refuses the list for a software that has no plugin platform at all (Vanilla) the way
         * every other plugin endpoint refuses it: `409 FEATURE_UNAVAILABLE` with
         * `{ feature: "plugins.list", reason: "NOT_SUPPORTED" }`, which the panel turns into its
         * notice instead of an error state (§2.4.35).
         *
         * Only that permanent absence is refused. Every other reason the list has no source — a
         * node that is offline, a plugin without `plugins` — is a state that changes, and still
         * answers 200 with whatever there is, exactly as before; the panel explains those from
         * the server row's `features.reasons`.
         */
        fun refuseUnsupported(inputs: ServerFeatureInputs) {
            if (!inputs.supportsPlugins) {
                ServerFeatureResolver.pick(inputs, ServerFeature.PLUGINS_LIST)
            }
        }
    }
}
