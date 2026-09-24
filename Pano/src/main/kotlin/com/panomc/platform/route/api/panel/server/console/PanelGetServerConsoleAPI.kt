package com.panomc.platform.route.api.panel.server.console

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.panel.permission.ManageServerConsolePermission
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.Server
import com.panomc.platform.error.NotExists
import com.panomc.platform.model.*
import com.panomc.platform.node.NodeManager
import com.panomc.platform.node.message.ConsoleHistoryMessage as NodeConsoleHistoryMessage
import com.panomc.platform.panel.PanelRealtimeHub
import com.panomc.platform.server.ServerCapability
import com.panomc.platform.server.ServerManager
import com.panomc.platform.server.console.ConsoleSearch
import com.panomc.platform.server.console.NodeConsoleHistory
import com.panomc.platform.server.event.request.ConsoleHistoryResultEventRequest
import com.panomc.platform.server.message.ConsoleHistoryMessage as PluginConsoleHistoryMessage
import com.panomc.platform.server.console.ServerConsoleBuffer
import com.panomc.platform.server.dto.ConsoleLineData
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Parameters.optionalParam
import io.vertx.ext.web.validation.builder.Parameters.param
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.intSchema
import io.vertx.json.schema.common.dsl.Schemas.numberSchema
import io.vertx.json.schema.common.dsl.Schemas.stringSchema
import com.panomc.platform.util.UsageMode

/**
 * Serves one page of a server's console history so the panel has something to show the moment the
 * console page opens, before the first live line arrives over the realtime hub.
 *
 * History is readable even when the server is offline or never announced the console capability:
 * `streaming` and `capable` tell the panel which of the two it is looking at, so it can show the
 * lines that led up to a crash and disable the input instead of hiding the page.
 *
 * Pano's own buffer is only ever half the history. A server streams its output while somebody is
 * watching it and not otherwise, so a server that crashed overnight explained itself into the
 * node's ring buffer — or into its own log file — and into nothing here. When this buffer holds
 * less than what was asked for, the source's copy is pulled over the socket and merged in, which
 * is how the console page can open on the reason a server died rather than on an empty screen.
 *
 * `skip` is what turns that into paging: it says how many of the newest lines the panel already
 * shows, so asking again gives the page *before* this one. Past the first page Pano's own buffer
 * is of no use — it only ever holds the newest lines — so those pages come from the source alone,
 * which reads them back out of the server's log files.
 *
 * `query` turns the page into a search (§2.4.20): the source searches the window "Load older"
 * could reach, `skip` and `limit` count matches, and the response echoes the query it answered.
 * See [search] for how the two sources and Pano's own buffer share that job.
 */
@Endpoint
class PanelGetServerConsoleAPI(
    private val databaseManager: DatabaseManager,
    private val authProvider: AuthProvider,
    private val serverManager: ServerManager,
    private val nodeManager: NodeManager,
    private val panelRealtimeHub: PanelRealtimeHub
) : PanelApi() {
    override val usageModes = UsageMode.WITH_SERVERS

    override val paths = listOf(Path("/api/panel/servers/:id/console", RouteType.GET))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .pathParameter(param("id", numberSchema()))
            .queryParameter(optionalParam("limit", intSchema()))
            .queryParameter(optionalParam("skip", intSchema()))
            .queryParameter(optionalParam("query", stringSchema()))
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        val parameters = getParameters(context)
        val id = parameters.pathParameter("id").long

        authProvider.requirePermission(ManageServerConsolePermission(), context, id)

        val limit = (parameters.queryParameter("limit")?.integer ?: DEFAULT_LIMIT)
            .coerceIn(1, ServerConsoleBuffer.DEFAULT_CAPACITY)

        val skip = (parameters.queryParameter("skip")?.integer ?: 0).coerceIn(0, MAX_SKIP)

        // Blank is not a search: an empty Find box is the plain console, exactly as before.
        val query = ConsoleSearch.needle(parameters.queryParameter("query")?.string)

        val sqlClient = getSqlClient()

        val server = databaseManager.serverDao.getById(id, sqlClient) ?: throw NotExists()

        if (!server.permissionGranted) {
            throw NotExists()
        }

        val buffer = serverManager.getConsoleBuffer(id)

        // Older pages are the source's answer verbatim. Merging them with a buffer that holds a
        // different part of the history would splice two windows together and call the result one
        // page, and `dropped` counts what was lost from the live stream, which an older page was
        // never part of.
        val page = when {
            query != null -> search(server, buffer, limit, skip, query)
            skip > 0 -> fromSource(server, limit, skip)
            else -> firstPage(server, buffer, limit)
        }

        // Colour spans ride along as `c` when a line has them (§2.4.21 D); every source's lines
        // were validated on the way in, and a search keeps a line whole, spans included.
        val lines = page.lines.map { line -> line.toJsonObject() }

        return Successful(
            mapOf(
                "lines" to lines,
                // What fell out of the live stream is a fact about the newest plain page only;
                // an older page or a search result was never part of it.
                "dropped" to if (skip > 0 || query != null) 0L else buffer.getDroppedCount(),
                "hasMore" to page.hasMore,
                "streaming" to (
                    panelRealtimeHub.isConsoleStreaming(id) &&
                        (serverManager.isConnected(id) || server.isManaged)
                    ),
                // A managed server always has a console, whether or not a plugin is installed:
                // the node pipes the process's own stdout into the same buffer.
                "capable" to (server.isManaged || server.hasCapability(ServerCapability.CONSOLE)),
                // The query this page answers, normalised the way it was searched for (trimmed,
                // capped), or null for the plain console.
                "query" to query
            )
        )
    }

    /**
     * One page of matches for [query] (§2.4.20).
     *
     * The source answers when it can: it holds the whole searchable window, the log files, where
     * Pano holds only the newest lines. Its answer is filtered again here whatever it claims,
     * because a node or plugin that predates the field ignores it and sends a plain page — and a
     * line that does not match must never be shown as a match. Only when no source can answer at
     * all (offline, too old to know the message, console capture off) is Pano's own buffer
     * searched instead, with the same rules, so Find is never simply blind.
     *
     * The two are not merged. Everything the buffer holds is also in the source's window, and a
     * merge would count some matches twice and make `skip` mean something different on the next
     * page, which comes from the source alone.
     */
    private suspend fun search(
        server: Server,
        buffer: ServerConsoleBuffer,
        limit: Int,
        skip: Int,
        query: String
    ): History {
        val source = fromSource(server, limit, skip, query)

        if (source.answered) {
            return History(ConsoleSearch.filter(source.lines, query), source.hasMore)
        }

        val local = ConsoleSearch.page(buffer.snapshot(), limit, skip, query)

        return History(local.lines, local.hasMore)
    }

    /**
     * The newest page: Pano's own buffer, topped up from the source when it is short.
     *
     * Only when it is short — a console somebody has been watching all along already holds
     * everything, and pulling the source's copy of it every time the page opens would be a socket
     * round trip for nothing. A full page always offers an older one: whatever fell out of the
     * top of it is still somewhere, in the buffer or in a log file.
     */
    private suspend fun firstPage(server: Server, buffer: ServerConsoleBuffer, limit: Int): History {
        val local = buffer.snapshot(limit)

        if (local.size >= limit) {
            return History(local, true)
        }

        val source = fromSource(server, limit, 0)

        if (source.lines.isEmpty()) {
            return History(local, source.hasMore || local.size >= limit)
        }

        val merged = NodeConsoleHistory.merge(local, source.lines, limit)

        return History(merged, source.hasMore || merged.size >= limit)
    }

    /** One page from whatever holds this server's history, or nothing at all. */
    private suspend fun fromSource(server: Server, limit: Int, skip: Int, query: String? = null): History =
        if (server.isManaged) fromNode(server, limit, skip, query) else fromPlugin(server, limit, skip, query)

    /**
     * The node's history for [server], or nothing at all.
     *
     * Every failure is silence on purpose: a node that is offline, slow or too old to know the
     * message must not turn "show me the console" into an error page when Pano has lines of its
     * own to show.
     */
    private suspend fun fromNode(server: Server, limit: Int, skip: Int, query: String?): History {
        val nodeId = server.nodeId ?: return History.NONE
        val uuid = server.uuid ?: return History.NONE

        if (!nodeManager.isConnected(nodeId)) {
            return History.NONE
        }

        return try {
            val payload =
                nodeManager.request(nodeId, NodeConsoleHistoryMessage(uuid, limit, skip, query), SOURCE_TIMEOUT_MS)

            if (!payload.getBoolean("ok", false)) {
                return History.NONE
            }

            History(NodeConsoleHistory.parse(payload), payload.getBoolean("hasMore", false))
        } catch (_: Exception) {
            History.NONE
        }
    }

    /**
     * The plugin's own history for [server], or nothing at all.
     *
     * A linked server has no node reading its log files, so the plugin reads them itself: it is
     * the only thing on that machine Pano can ask. Silence covers every way that can fail — the
     * server is offline, it never announced a console, its plugin is too old to know the message,
     * or console capture is switched off in its config — because none of them is a reason to
     * refuse to show the lines Pano already has.
     */
    private suspend fun fromPlugin(server: Server, limit: Int, skip: Int, query: String?): History {
        if (!serverManager.isConnected(server.id) || !server.hasCapability(ServerCapability.CONSOLE)) {
            return History.NONE
        }

        return try {
            val reply = serverManager.request(
                server.id,
                PluginConsoleHistoryMessage(limit, skip, query),
                SOURCE_TIMEOUT_MS
            ) as? ConsoleHistoryResultEventRequest ?: return History.NONE

            History(NodeConsoleHistory.parsePlugin(reply.lines), reply.hasMore == true)
        } catch (_: Exception) {
            History.NONE
        }
    }

    /**
     * One page of history and whether the source that gave it holds an older one.
     *
     * [answered] tells an empty page apart from no page at all: a search that found nothing is an
     * answer, a node that is offline is not, and only the second falls back to Pano's own buffer.
     */
    private data class History(
        val lines: List<ConsoleLineData>,
        val hasMore: Boolean,
        val answered: Boolean = true
    ) {
        companion object {
            val NONE = History(emptyList(), false, answered = false)
        }
    }

    companion object {
        private const val DEFAULT_LIMIT = 500

        /**
         * How far back paging may go, mirroring the reader's own ceiling: every page is found by
         * reading a log file backwards from its end, so a page costs the lines it steps over.
         */
        private const val MAX_SKIP = 20_000

        /** Shorter than a file operation's: a page load is waiting on this, and it is optional. */
        private const val SOURCE_TIMEOUT_MS = 5_000L
    }
}
