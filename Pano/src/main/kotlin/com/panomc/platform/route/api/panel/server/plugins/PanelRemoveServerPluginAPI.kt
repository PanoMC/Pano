package com.panomc.platform.route.api.panel.server.plugins

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.panel.log.ServerPluginFileActionLog
import com.panomc.platform.auth.panel.permission.ManageServerPluginsPermission
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.error.InvalidData
import com.panomc.platform.error.NotExists
import com.panomc.platform.error.PathDenied
import com.panomc.platform.model.*
import com.panomc.platform.node.ManagedServerFileClient
import com.panomc.platform.node.message.FileDeleteMessage
import com.panomc.platform.panel.PanelRealtimeHub
import com.panomc.platform.server.plugins.PluginFileNaming
import com.panomc.platform.server.plugins.PluginLoaderMapping
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

/**
 * Removes one jar from a managed server's plugin directory.
 *
 * Refuses the Pano plugin itself: it is the link this very request travelled over, and an admin
 * who deletes it loses the console, the player list and the power buttons for that server with no
 * way to put it back from the panel.
 */
@Endpoint
class PanelRemoveServerPluginAPI(
    private val databaseManager: DatabaseManager,
    private val authProvider: AuthProvider,
    private val fileClient: ManagedServerFileClient,
    private val panelRealtimeHub: PanelRealtimeHub
) : PanelApi() {
    override val paths = listOf(Path("/api/panel/servers/:id/plugins/remove", RouteType.POST))

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

        val filename = parameters.body().jsonObject.getString("filename").orEmpty().trim()

        if (!PluginFileNaming.isJarName(filename)) {
            throw InvalidData()
        }

        if (PluginFileNaming.isPanoPluginJar(filename)) {
            throw PathDenied()
        }

        val sqlClient = getSqlClient()
        val target = fileClient.resolve(id, sqlClient)

        val directory = PluginLoaderMapping.targetDir(target.server.type)
        val path = "$directory/$filename"

        fileClient.request(target, FileDeleteMessage(target.serverUuid, listOf(path)))

        // The provenance goes with the file. Leaving it would claim a version this server has not
        // had since a moment ago, and would offer an update for it.
        databaseManager.serverPluginInstallDao.deleteByServerIdAndFilename(id, filename, sqlClient)

        val userId = authProvider.getUserIdFromRoutingContext(context)
        val username = databaseManager.userDao.getUsernameFromUserId(userId, sqlClient) ?: throw NotExists()

        databaseManager.panelActivityLogDao.add(
            ServerPluginFileActionLog(
                userId,
                username,
                id,
                ServerPluginFileActionLog.ACTION_REMOVE,
                filename
            ),
            sqlClient
        )

        panelRealtimeHub.pushServerPluginsChanged(id)
        panelRealtimeHub.pushServerFilesChanged(id, directory)

        return Successful()
    }
}
