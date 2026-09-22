package com.panomc.platform.route.api.panel.server.metrics

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.panel.permission.ManageServersPermission
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.ServerMetric
import com.panomc.platform.error.BadRequest
import com.panomc.platform.model.*
import com.panomc.platform.node.NodeManager
import com.panomc.platform.server.ServerManager
import com.panomc.platform.server.metrics.MetricsBucket
import com.panomc.platform.server.metrics.MetricsRange
import com.panomc.platform.server.metrics.MetricsSeriesWindow
import com.panomc.platform.server.metrics.ServerLatestMetrics
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Parameters.optionalParam
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.stringSchema

/**
 * Every visible server's vitals in one response, for the cards in the servers modal (§2.4.18 B).
 *
 * The modal draws a sparkline and three live numbers on each card and re-asks every fifteen
 * seconds while it is open, so this exists to make that one request and one bucketed query
 * instead of two per server per refresh.
 *
 * The path is deliberately `servers-metrics` rather than anything under `/servers/`: the servers
 * routes take a numeric `:id`, and a sibling segment there would sooner or later be swallowed by
 * one of them.
 */
@Endpoint
class PanelGetServersMetricsAPI(
    private val databaseManager: DatabaseManager,
    private val authProvider: AuthProvider,
    private val serverManager: ServerManager,
    private val nodeManager: NodeManager
) : PanelApi() {
    override val paths = listOf(Path("/api/panel/servers-metrics", RouteType.GET))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .queryParameter(optionalParam("range", stringSchema()))
            // Checked in the handler rather than read leniently like `range`: an override that is not
            // one of the three would otherwise be a silent fallback to the range's own buckets.
            .queryParameter(optionalParam("bucket", stringSchema()))
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        authProvider.requirePermission(ManageServersPermission(), context)

        val parameters = getParameters(context)

        val range = MetricsRange.fromId(parameters.queryParameter("range")?.string)

        val bucket = parameters.queryParameter("bucket")?.string?.let { requested ->
            MetricsBucket.fromId(requested)
                ?: throw BadRequest(extras = mapOf("message" to "\"$requested\" is not a bucket: minute, hour or day."))
        }

        val window = MetricsSeriesWindow.of(range, bucket)
            ?: throw BadRequest(
                extras = mapOf("message" to "Too many points: pick a coarser bucket for the ${range.id} range.")
            )

        val sqlClient = getSqlClient()

        // The same set the servers modal lists, and the same one the panel is allowed to see: a
        // server whose connect request has not been accepted has no vitals to show.
        val servers = databaseManager.serverDao.getAllByPermissionGranted(sqlClient)

        val now = System.currentTimeMillis()

        val seriesByServerId = databaseManager.serverMetricDao.getSeriesForServers(
            servers.map { it.id },
            now - range.durationMs,
            now,
            window.bucketMs,
            sqlClient,
            window.offsetMs(now)
        )

        val byServer = JsonObject()

        servers.forEach { server ->
            byServer.put(
                server.id.toString(),
                JsonObject()
                    // The live sample, which is where a card's CPU, RAM and disk numbers come
                    // from; null for a server that has reported nothing since Pano started. The
                    // host's memory rides along with it, because that is what a node-sourced RAM
                    // figure has to be divided by (see ServerLatestMetrics).
                    .put(
                        "latest",
                        ServerLatestMetrics.latestJson(
                            serverManager.getLatestMetrics(server.id),
                            ServerLatestMetrics.hostMemTotal(
                                server.nodeId?.let { nodeManager.getLatestMetrics(it) }
                            )
                        )
                    )
                    .put("series", JsonArray(seriesByServerId[server.id].orEmpty().map { point(it, window) }))
            )
        }

        return Successful(
            mapOf(
                "range" to range.id,
                "bucketMs" to window.bucketMs,
                "servers" to byServer
            )
        )
    }

    /** One point of a card's sparkline, keyed as [window] plots it. */
    private fun point(metric: ServerMetric, window: MetricsSeriesWindow) = JsonObject()
        .put("ts", window.key(metric.ts))
        .put("cpu", metric.cpu)
        .put("memUsed", metric.memUsed)
        .put("memMax", metric.memMax)
        .put("players", metric.players)
        .put("tps", metric.tps)
        .put("diskUsed", metric.diskUsed)
        .put("netRx", metric.netRx)
        .put("netTx", metric.netTx)
        .put("source", metric.source)
}
