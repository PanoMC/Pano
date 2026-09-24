package com.panomc.platform.route.api.panel.server.startup

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.panel.permission.ManageServerStartupPermission
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.error.NotExists
import com.panomc.platform.error.ServerCapabilityMissing
import com.panomc.platform.model.*
import com.panomc.platform.node.ManagedServerFileClient
import com.panomc.platform.node.message.FileReadMessage
import com.panomc.platform.server.ServerPropertyKeys
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Parameters.param
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.numberSchema
import com.panomc.platform.util.UsageMode

/**
 * What a managed server's `server.properties` holds right now, for the Server properties page.
 *
 * Two maps, because they are two different truths. `file` is the file as it is on the node at
 * this moment — the values the server will actually start with, hand edits included — and is
 * what the page prefills from. `stored` is what Pano has been asked to manage; it is what the
 * page falls back to when the node cannot be reached, and the page says so, because a form that
 * quietly showed last month's values over a file somebody edited since would write them back.
 *
 * Read-only and cheap, so it takes the startup permission rather than the files one: the page
 * is a settings page, not a file manager.
 */
@Endpoint
class PanelGetServerStartupPropertiesAPI(
    private val databaseManager: DatabaseManager,
    private val authProvider: AuthProvider,
    private val fileClient: ManagedServerFileClient
) : PanelApi() {
    override val usageModes = UsageMode.WITH_SERVERS

    override val paths = listOf(Path("/api/panel/servers/:id/startup/properties", RouteType.GET))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .pathParameter(param("id", numberSchema()))
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        val parameters = getParameters(context)
        val id = parameters.pathParameter("id").long

        authProvider.requirePermission(ManageServerStartupPermission(), context, id)

        val sqlClient = getSqlClient()

        val server = databaseManager.serverDao.getById(id, sqlClient) ?: throw NotExists()

        if (!server.permissionGranted) {
            throw NotExists()
        }

        if (!server.isManaged) {
            throw ServerCapabilityMissing()
        }

        // Best effort: a node that is offline, a server that has never started (no file yet) or
        // a read that takes too long all mean "no file to show", never an error page.
        val file = try {
            val target = fileClient.resolve(id, sqlClient)
            val payload = fileClient.request(
                target,
                FileReadMessage(target.serverUuid, "server.properties", MAX_FILE_BYTES),
                READ_TIMEOUT_MS
            )

            ServerPropertyKeys.parseFile(payload.getString("content"))
        } catch (_: Exception) {
            null
        }

        return Successful(
            mapOf(
                "file" to file?.let { ServerPropertyKeys.toJsonObject(it) },
                "stored" to ServerPropertyKeys.toJsonObject(server.properties),
                "reserved" to ServerPropertyKeys.RESERVED.toList()
            )
        )
    }

    companion object {
        /** A vanilla file is a few kilobytes; this is plenty even with a long generator-settings. */
        private const val MAX_FILE_BYTES = 256 * 1024

        /** How long the page waits for the node before showing the stored values instead. */
        private const val READ_TIMEOUT_MS = 4_000L
    }
}
