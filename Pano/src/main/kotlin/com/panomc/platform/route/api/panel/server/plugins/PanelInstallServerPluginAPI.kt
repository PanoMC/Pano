package com.panomc.platform.route.api.panel.server.plugins

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.panel.log.ServerPluginFileActionLog
import com.panomc.platform.auth.panel.permission.ManageServerPluginsPermission
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.error.InvalidData
import com.panomc.platform.error.NotExists
import com.panomc.platform.error.ServerCapabilityMissing
import com.panomc.platform.error.RateLimited
import com.panomc.platform.model.*
import com.panomc.platform.server.feature.ServerFeature
import com.panomc.platform.node.ManagedServerFileClient
import com.panomc.platform.server.ServerManager
import com.panomc.platform.server.plugins.ManagedServerPluginService
import com.panomc.platform.server.plugins.PluginFileNaming
import com.panomc.platform.server.plugins.PluginLoaderMapping
import com.panomc.platform.server.plugins.PluginSourceCatalog
import com.panomc.platform.server.plugins.PluginSourceId
import com.panomc.platform.server.console.ServerActionRateLimiter
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.RequestPredicate
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Bodies.json
import io.vertx.ext.web.validation.builder.Parameters.param
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.intSchema
import io.vertx.json.schema.common.dsl.Schemas.numberSchema
import io.vertx.json.schema.common.dsl.Schemas.objectSchema
import io.vertx.json.schema.common.dsl.Schemas.stringSchema

/**
 * Installs one plugin or mod version onto a managed server.
 *
 * Pano resolves the version here rather than trusting a URL from the browser: a panel that could
 * name the download would be a panel that could make a node fetch anything from anywhere. The
 * only thing that crosses from the client is which project and version, and everything the node
 * receives is read back out of the source's own answer.
 *
 * An already-installed build of the same plugin is passed along as `replaceFilename`, so an update
 * does not leave a server loading two copies of the same jar.
 */
@Endpoint
class PanelInstallServerPluginAPI(
    private val databaseManager: DatabaseManager,
    private val authProvider: AuthProvider,
    private val fileClient: ManagedServerFileClient,
    private val serverManager: ServerManager,
    private val pluginSourceCatalog: PluginSourceCatalog,
    private val pluginService: ManagedServerPluginService,
    private val serverActionRateLimiter: ServerActionRateLimiter
) : PanelApi() {
    override val paths = listOf(Path("/api/panel/servers/:id/plugins/install", RouteType.POST))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .pathParameter(param("id", numberSchema()))
            .body(
                json(
                    objectSchema()
                        .requiredProperty("source", stringSchema())
                        .requiredProperty("projectId", stringSchema())
                        .requiredProperty("versionId", stringSchema())
                        .optionalProperty("fileIndex", intSchema())
                )
            )
            .predicate(RequestPredicate.BODY_REQUIRED)
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        val parameters = getParameters(context)
        val id = parameters.pathParameter("id").long

        authProvider.requirePermission(ManageServerPluginsPermission(), context, id)

        // §2.4.12. An install downloads from a third party, and hammering Modrinth through Pano is how an install gets Pano's IP blocked.
        if (!serverActionRateLimiter.tryAcquire(
                ServerActionRateLimiter.Action.PLUGIN_INSTALL,
                authProvider.getUserIdFromRoutingContext(context),
                id
            )
        ) {
            throw RateLimited()
        }

        val body = parameters.body().jsonObject

        val source = PluginSourceId.fromId(body.getString("source")) ?: throw InvalidData()
        val projectId = body.getString("projectId").orEmpty()
        val versionId = body.getString("versionId").orEmpty()
        val fileIndex = body.getInteger("fileIndex", 0) ?: 0

        if (projectId.isBlank() || versionId.isBlank()) {
            throw InvalidData()
        }

        val sqlClient = getSqlClient()

        // Managed only, and the same resolve every file operation goes through: linked servers
        // report ServerCapabilityMissing rather than a not-found, because the server exists, it
        // simply has no files Pano can reach.
        val target = fileClient.resolve(id, sqlClient, ServerFeature.PLUGINS_INSTALL)

        if (!PluginLoaderMapping.supportsPlugins(target.server.type)) {
            throw ServerCapabilityMissing()
        }

        if (!pluginSourceCatalog.isEnabled(source, target.server.type)) {
            throw ServerCapabilityMissing()
        }

        val version = pluginSourceCatalog.version(
            source = source,
            type = target.server.type,
            softwareVersion = target.server.softwareVersion ?: target.server.version,
            projectId = projectId,
            versionId = versionId
        ) ?: throw NotExists()

        val file = version.files.getOrNull(fileIndex) ?: throw NotExists()

        // A source that only links out to the author's own release page has nothing the node can
        // download and verify; what is behind that URL is a web page, not a jar.
        if (file.external || file.url.isNullOrBlank()) {
            throw InvalidData(extras = mapOf("reason" to "EXTERNAL_DOWNLOAD"))
        }

        val filename = PluginFileNaming.sanitise(file.filename, fallback = version.id)

        val existing = pluginService
            .listFiles(target, serverManager.getInstalledPlugins(id).orEmpty())
            .map { it.filename }

        val replaceFilename = PluginFileNaming.replacementFor(filename, existing)
            ?.takeIf { !PluginFileNaming.isPanoPluginJar(it) }

        val task = pluginService.install(
            target = target,
            source = source,
            projectId = projectId,
            version = version,
            file = file,
            filename = filename,
            replaceFilename = replaceFilename,
            createdBy = authProvider.getUserIdFromRoutingContext(context),
            sqlClient = sqlClient
        )

        val userId = authProvider.getUserIdFromRoutingContext(context)
        val username = databaseManager.userDao.getUsernameFromUserId(userId, sqlClient) ?: throw NotExists()

        databaseManager.panelActivityLogDao.add(
            ServerPluginFileActionLog(
                userId,
                username,
                id,
                ServerPluginFileActionLog.ACTION_INSTALL,
                filename,
                source.id,
                projectId
            ),
            sqlClient
        )

        return Successful(
            mapOf(
                "taskId" to task.id,
                "taskUuid" to task.uuid,
                "filename" to filename,
                "replacedFilename" to replaceFilename
            )
        )
    }
}
