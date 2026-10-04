package com.panomc.platform.route.api.panel.server.plugins

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.panel.log.ServerPluginUpdatedLog
import com.panomc.platform.auth.panel.permission.ManageServerPluginsPermission
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.error.InvalidData
import com.panomc.platform.error.NotExists
import com.panomc.platform.error.PathDenied
import com.panomc.platform.error.PluginUpToDate
import com.panomc.platform.error.RateLimited
import com.panomc.platform.model.*
import com.panomc.platform.server.feature.ServerFeature
import com.panomc.platform.node.ManagedServerFileClient
import com.panomc.platform.server.console.ServerActionRateLimiter
import com.panomc.platform.server.plugins.ManagedServerPluginService
import com.panomc.platform.server.plugins.PluginFileNaming
import com.panomc.platform.server.plugins.PluginUpdateService
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.RequestPredicate
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Bodies.json
import io.vertx.ext.web.validation.builder.Parameters.param
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.numberSchema
import io.vertx.json.schema.common.dsl.Schemas.objectSchema
import io.vertx.json.schema.common.dsl.Schemas.stringSchema
import com.panomc.platform.util.UsageMode

/**
 * Updates one tracked jar to the newest build its source offers.
 *
 * Nothing about which version crosses from the browser: the client names a file, and everything
 * else — which project that file is, which version is newest, which of its files is the jar — is
 * read out of what Pano recorded and what the source answers. A panel that could name the version
 * would be a panel that could install any file from any project over any other.
 */
@Endpoint
class PanelUpdateServerPluginAPI(
    private val databaseManager: DatabaseManager,
    private val authProvider: AuthProvider,
    private val fileClient: ManagedServerFileClient,
    private val pluginService: ManagedServerPluginService,
    private val pluginUpdateService: PluginUpdateService,
    private val serverActionRateLimiter: ServerActionRateLimiter
) : PanelApi() {
    override val usageModes = UsageMode.WITH_SERVERS

    override val paths = listOf(Path("/api/panel/servers/:id/plugins/update", RouteType.POST))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .pathParameter(param("id", numberSchema()))
            .body(json(objectSchema().requiredProperty("filename", stringSchema())))
            .predicate(RequestPredicate.BODY_REQUIRED)
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        val parameters = getParameters(context)
        val id = parameters.pathParameter("id").long

        authProvider.requirePermission(ManageServerPluginsPermission(), context, id)

        val userId = authProvider.getUserIdFromRoutingContext(context)

        // The same limit an install is under: an update is an install with a delete after it.
        if (!serverActionRateLimiter.tryAcquire(ServerActionRateLimiter.Action.PLUGIN_INSTALL, userId, id)) {
            throw RateLimited()
        }

        val filename = parameters.body().jsonObject.getString("filename").orEmpty().trim()

        if (!PluginFileNaming.isJarName(filename)) {
            throw InvalidData()
        }

        if (PluginFileNaming.isPanoPluginJar(filename)) {
            throw PathDenied()
        }

        val sqlClient = getSqlClient()

        val target = fileClient.resolve(id, sqlClient, ServerFeature.PLUGINS_INSTALL)

        val row = databaseManager.serverPluginInstallDao.getByServerIdAndFilename(id, filename, sqlClient)
            ?: throw NotExists()

        val plan = when (val attempt = pluginUpdateService.plan(target.server, row)) {
            is PluginUpdateService.UpdateAttempt.Ready -> attempt.plan

            is PluginUpdateService.UpdateAttempt.Refused -> throw refusal(attempt.reason)
        }

        val task = pluginService.install(
            target = target,
            source = plan.source,
            projectId = row.projectId,
            version = plan.version,
            file = plan.file,
            filename = plan.filename,
            // Exactly the file being replaced, not a guess from the name: this is the one case
            // where Pano knows which jar the new one supersedes rather than inferring it.
            replaceFilename = row.filename,
            createdBy = userId,
            sqlClient = sqlClient
        )

        val username = databaseManager.userDao.getUsernameFromUserId(userId, sqlClient) ?: throw NotExists()

        databaseManager.panelActivityLogDao.add(
            ServerPluginUpdatedLog(
                userId = userId,
                username = username,
                serverId = id,
                serverName = target.server.customName ?: target.server.name,
                pluginName = row.projectName ?: row.filename,
                fromVersion = row.versionNumber,
                toVersion = plan.version.versionNumber ?: plan.version.name
            ),
            sqlClient
        )

        return Successful(
            mapOf(
                "taskId" to task.id,
                "taskUuid" to task.uuid,
                "filename" to plan.filename
            )
        )
    }

    private fun refusal(reason: String): Throwable = when (reason) {
        PluginUpdateService.REFUSED_UP_TO_DATE -> PluginUpToDate()
        PluginUpdateService.REFUSED_PANO_PLUGIN -> PathDenied()
        PluginUpdateService.REFUSED_EXTERNAL -> InvalidData(extras = mapOf("reason" to reason))
        else -> NotExists()
    }
}
