package com.panomc.platform.route.api.panel.server.plugins

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.panel.permission.ManageServerPluginsPermission
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.error.FailedToUpdateResource
import com.panomc.platform.error.FeatureUnavailable
import com.panomc.platform.error.NodeOffline
import com.panomc.platform.error.NotExists
import com.panomc.platform.error.PluginUpToDate
import com.panomc.platform.error.RateLimited
import com.panomc.platform.error.ServerCapabilityMissing
import com.panomc.platform.error.ServerOffline
import com.panomc.platform.model.*
import com.panomc.platform.server.console.ServerActionRateLimiter
import com.panomc.platform.server.plugins.PanoPluginUpdatePlan
import com.panomc.platform.server.plugins.PanoPluginUpdateService
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Parameters.param
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.numberSchema

/**
 * Updates the Pano plugin on one server (`POST /api/panel/servers/:id/pano-plugin/update`).
 *
 * The button behind the Overview's "update available" badge. Which route the update takes is
 * decided by [PanoPluginUpdateService] and reported back as `mode` — `node` when the server's node
 * writes the new jar, `plugin` when the plugin downloads and stages its own successor — and either
 * way the answer is a task to follow and `restartRequired: true`, because no platform the plugin
 * runs on loads a new jar into a running server.
 *
 * Under the server plugins permission and the same rate limit as any plugin install: replacing the
 * Pano jar is a plugin install, just one whose failure would be felt more.
 *
 * Refusals keep their reason in `extras.reason` (`PanoPluginUpdatePlan.REASON_*`), so the panel can
 * say "the node is offline" or "this plugin is too old to update itself" instead of a generic error.
 * When nothing Pano can reach is able to write the jar but a person can — a linked server whose
 * plugin predates self-update, or one that is not connected at all — `extras.manual` is true and
 * the panel offers the jar from `GET /api/panel/servers/:id/pano-plugin/jar` with the steps to put
 * it in place by hand. Once that build is running, the next update is one click again.
 */
@Endpoint
class PanelUpdatePanoPluginAPI(
    private val databaseManager: DatabaseManager,
    private val authProvider: AuthProvider,
    private val panoPluginUpdateService: PanoPluginUpdateService,
    private val serverActionRateLimiter: ServerActionRateLimiter
) : PanelApi() {
    override val paths = listOf(Path("/api/panel/servers/:id/pano-plugin/update", RouteType.POST))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .pathParameter(param("id", numberSchema()))
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        val id = getParameters(context).pathParameter("id").long

        authProvider.requirePermission(ManageServerPluginsPermission(), context, id)

        val userId = authProvider.getUserIdFromRoutingContext(context)

        if (!serverActionRateLimiter.tryAcquire(ServerActionRateLimiter.Action.PLUGIN_INSTALL, userId, id)) {
            throw RateLimited()
        }

        val sqlClient = getSqlClient()

        val server = databaseManager.serverDao.getById(id, sqlClient) ?: throw NotExists()

        // A connect request nobody has accepted yet is not a server Pano manages anything on.
        if (!server.permissionGranted) {
            throw NotExists()
        }

        return when (val attempt = panoPluginUpdateService.start(server, userId, sqlClient)) {
            is PanoPluginUpdateService.Attempt.Started -> Successful(
                mapOf(
                    "taskId" to attempt.task.id,
                    "taskUuid" to attempt.task.uuid,
                    "mode" to attempt.mode.wire,
                    "fromVersion" to attempt.fromVersion,
                    "toVersion" to attempt.toVersion,
                    "restartRequired" to true
                )
            )

            is PanoPluginUpdateService.Attempt.Refused -> throw refusal(
                attempt.reason,
                manual = PanoPluginUpdatePlan.canUpdateByHand(attempt.reason, server.isManaged)
            )
        }
    }

    companion object {
        /** The dotted feature name refusals carry, alongside the other `FeatureUnavailable`s. */
        const val FEATURE = "panoPlugin.update"

        /** The error a refusal becomes; public so the mapping can be read next to the reasons. */
        fun refusal(reason: String, manual: Boolean = false): Throwable {
            val extras = mapOf("feature" to FEATURE, "reason" to reason, "manual" to manual)

            return when (reason) {
                PanoPluginUpdatePlan.REASON_NO_PLUGIN_MODULE -> ServerCapabilityMissing(extras = extras)
                PanoPluginUpdatePlan.REASON_UP_TO_DATE -> PluginUpToDate(extras = extras)
                PanoPluginUpdatePlan.REASON_JAR_UNAVAILABLE -> FailedToUpdateResource(extras = extras)
                PanoPluginUpdatePlan.REASON_SERVER_OFFLINE -> ServerOffline(extras = extras)
                PanoPluginUpdatePlan.REASON_NODE_OFFLINE -> NodeOffline(extras = extras)
                else -> FeatureUnavailable(extras = extras)
            }
        }
    }
}
