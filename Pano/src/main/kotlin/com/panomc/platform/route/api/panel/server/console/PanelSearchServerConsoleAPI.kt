package com.panomc.platform.route.api.panel.server.console

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.panel.permission.ManageServerConsolePermission
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.Server
import com.panomc.platform.error.NodeOffline
import com.panomc.platform.error.NotExists
import com.panomc.platform.error.ServerOffline
import com.panomc.platform.model.*
import com.panomc.platform.node.NodeManager
import com.panomc.platform.node.message.ConsoleSearchMessage as NodeConsoleSearchMessage
import com.panomc.platform.server.ServerManager
import com.panomc.platform.server.console.ConsoleDeepSearch
import com.panomc.platform.server.event.request.ConsoleSearchResultEventRequest
import com.panomc.platform.server.feature.ServerFeature
import com.panomc.platform.server.feature.ServerFeatureResolver
import com.panomc.platform.server.feature.ServerFeatureSource
import com.panomc.platform.server.message.ConsoleSearchMessage as PluginConsoleSearchMessage
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Parameters.optionalParam
import io.vertx.ext.web.validation.builder.Parameters.param
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.intSchema
import io.vertx.json.schema.common.dsl.Schemas.numberSchema
import io.vertx.json.schema.common.dsl.Schemas.stringSchema

/**
 * One page of the console's deep search: every log file a server has, newest file first.
 *
 * Find in the panel used to search only the window "Load older" could reach (see
 * [PanelGetServerConsoleAPI]); this endpoint asks the source — the node for a managed server, the
 * plugin for a linked one, whichever `console.history` resolves to — to walk all of its log files
 * instead. It answers a page at a time: the panel shows what came back, sends the `cursor` it was
 * given, and keeps asking until `done`, or until the operator has seen enough and stops asking.
 * Nothing is held here between pages; the cursor carries all of it.
 *
 * Pano does no searching of its own. A source that cannot answer — offline, too old to know the
 * message (it simply never replies, and the timeout says so), or with its console capture switched
 * off — is an error the panel reads as "fall back to the windowed search", which still covers
 * Pano's own buffer. The one error it treats differently is `BAD_CURSOR`: the file the cursor named
 * has rotated away, and the answer is to start the search over.
 *
 * Same permission and the same visibility rule as the console history, because it is the same
 * history, only more of it. No rate limit beyond `/api`'s own: the paging is driven by a person
 * watching results arrive, and every page is bounded by the source's time budget.
 */
@Endpoint
class PanelSearchServerConsoleAPI(
    private val databaseManager: DatabaseManager,
    private val authProvider: AuthProvider,
    private val serverManager: ServerManager,
    private val nodeManager: NodeManager,
    private val serverFeatureResolver: ServerFeatureResolver
) : PanelApi() {
    override val paths = listOf(Path("/api/panel/servers/:id/console/search", RouteType.GET))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .pathParameter(param("id", numberSchema()))
            .queryParameter(optionalParam("query", stringSchema()))
            .queryParameter(optionalParam("cursor", stringSchema()))
            .queryParameter(optionalParam("limit", intSchema()))
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        val parameters = getParameters(context)
        val id = parameters.pathParameter("id").long

        authProvider.requirePermission(ManageServerConsolePermission(), context, id)

        // Checked before anything is looked up, so a malformed request costs no database read and
        // never reaches a socket.
        val query = ConsoleDeepSearch.query(parameters.queryParameter("query")?.string)
        val cursor = ConsoleDeepSearch.cursor(parameters.queryParameter("cursor")?.string)
        val limit = ConsoleDeepSearch.limit(parameters.queryParameter("limit")?.integer)

        val server = databaseManager.serverDao.getById(id, getSqlClient()) ?: throw NotExists()

        if (!server.permissionGranted) {
            throw NotExists()
        }

        val page = when (serverFeatureResolver.pick(server, ServerFeature.CONSOLE_HISTORY)) {
            ServerFeatureSource.NODE -> fromNode(server, query, cursor, limit)
            ServerFeatureSource.PLUGIN -> fromPlugin(server, query, cursor, limit)
            // Pano's own ring buffer is what the windowed search already covers; there are no
            // files behind it to search deeper.
            else -> throw ConsoleDeepSearch.unavailable()
        }

        return Successful(page.toMap(query))
    }

    /** One page from the node running [server]. Offline or silent is [NodeOffline], as everywhere. */
    private suspend fun fromNode(server: Server, query: String, cursor: String?, limit: Int): ConsoleDeepSearch.Page {
        val nodeId = server.nodeId ?: throw NodeOffline()
        val uuid = server.uuid ?: throw NotExists()

        val payload = nodeManager.request(
            nodeId,
            NodeConsoleSearchMessage(uuid, query, cursor, limit, ConsoleDeepSearch.BUDGET_MS),
            SOURCE_TIMEOUT_MS
        )

        return ConsoleDeepSearch.fromNode(payload)
    }

    /**
     * One page from [server]'s own plugin. Offline or silent is [ServerOffline]; an answer of some
     * other kind than a search result — which only a confused plugin could send on this `eventId` —
     * is treated the same as none.
     */
    private suspend fun fromPlugin(server: Server, query: String, cursor: String?, limit: Int): ConsoleDeepSearch.Page {
        val reply = serverManager.request(
            server.id,
            PluginConsoleSearchMessage(query, cursor, limit, ConsoleDeepSearch.BUDGET_MS),
            SOURCE_TIMEOUT_MS
        ) as? ConsoleSearchResultEventRequest ?: throw ServerOffline()

        return ConsoleDeepSearch.fromPlugin(reply)
    }

    companion object {
        /**
         * Generous next to a history page's five seconds: the source spends up to its time budget
         * reading, a single file bigger than the budget may run past it once, and then the page
         * still has to cross the socket.
         */
        private const val SOURCE_TIMEOUT_MS = 10_000L
    }
}
