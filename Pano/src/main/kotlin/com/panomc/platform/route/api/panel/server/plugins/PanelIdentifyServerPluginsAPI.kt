package com.panomc.platform.route.api.panel.server.plugins

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.panel.permission.ManageServerPluginsPermission
import com.panomc.platform.error.ServerCapabilityMissing
import com.panomc.platform.model.*
import com.panomc.platform.server.feature.ServerFeature
import com.panomc.platform.node.ManagedServerFileClient
import com.panomc.platform.server.ServerManager
import com.panomc.platform.server.plugins.ManagedServerPluginService
import com.panomc.platform.server.plugins.PluginFileNaming
import com.panomc.platform.server.plugins.PluginIdentificationService
import io.vertx.core.json.JsonArray
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Parameters.param
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.numberSchema

/**
 * Works out which project each unrecognised jar in a server's plugin directory is.
 *
 * On demand rather than only on a timer, because the moment somebody wants this is the moment they
 * are looking at a list of filenames wondering what half of them are. The answer is recorded, so
 * pressing it again on an unchanged directory is cheap and the update check has something to work
 * with from then on.
 */
@Endpoint
class PanelIdentifyServerPluginsAPI(
    private val authProvider: AuthProvider,
    private val fileClient: ManagedServerFileClient,
    private val serverManager: ServerManager,
    private val pluginService: ManagedServerPluginService,
    private val identificationService: PluginIdentificationService
) : PanelApi() {
    override val paths = listOf(Path("/api/panel/servers/:id/plugins/identify", RouteType.POST))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .pathParameter(param("id", numberSchema()))
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        val parameters = getParameters(context)
        val id = parameters.pathParameter("id").long

        authProvider.requirePermission(ManageServerPluginsPermission(), context, id)

        val sqlClient = getSqlClient()

        val target = fileClient.resolve(id, sqlClient, ServerFeature.PLUGINS_IDENTIFY)

        // An older daemon cannot hash anything, which is a missing capability rather than an
        // error: the same answer a linked server gets, for the same reason.
        if (!identificationService.isSupported(target)) {
            throw ServerCapabilityMissing(extras = mapOf("reason" to REASON_UNSUPPORTED))
        }

        val filenames = pluginService
            .listFiles(target, serverManager.getInstalledPlugins(id).orEmpty())
            .map { it.filename }
            .filterNot { PluginFileNaming.isPanoPluginJar(it) }

        val result = identificationService.identify(target, filenames, sqlClient)

        return Successful(
            mapOf(
                "identified" to JsonArray(result.identified.map { it.toJsonObject() }),
                "unknown" to JsonArray(result.unknown)
            )
        )
    }

    companion object {
        /** Told apart from a linked server's refusal, which carries no reason. */
        const val REASON_UNSUPPORTED = "NODE_TOO_OLD"
    }
}
