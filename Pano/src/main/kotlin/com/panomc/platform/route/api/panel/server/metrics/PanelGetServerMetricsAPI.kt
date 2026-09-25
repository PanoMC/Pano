package com.panomc.platform.route.api.panel.server.metrics

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.panel.permission.ManageServersPermission
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.error.BadRequest
import com.panomc.platform.error.NotExists
import com.panomc.platform.model.*
import com.panomc.platform.node.NodeManager
import com.panomc.platform.server.ServerCapability
import com.panomc.platform.server.ServerManager
import com.panomc.platform.server.feature.ServerFeatureResolver
import com.panomc.platform.server.metrics.MetricsBucket
import com.panomc.platform.server.metrics.MetricsRange
import com.panomc.platform.server.metrics.MetricsSeriesWindow
import com.panomc.platform.server.metrics.ServerLatestMetrics
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Parameters.optionalParam
import io.vertx.ext.web.validation.builder.Parameters.param
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.numberSchema
import io.vertx.json.schema.common.dsl.Schemas.stringSchema
import com.panomc.platform.util.UsageMode

/**
 * Serves one server's performance history for a chart, plus the live sample for the numbers next
 * to it.
 *
 * The ranges and their bucket sizes are [MetricsRange], shared with the bulk endpoint the servers
 * modal reads, so `range=1h` means the same window on a card's sparkline as it does here.
 */
@Endpoint
class PanelGetServerMetricsAPI(
    private val databaseManager: DatabaseManager,
    private val authProvider: AuthProvider,
    private val serverManager: ServerManager,
    private val serverFeatureResolver: ServerFeatureResolver,
    private val nodeManager: NodeManager
) : PanelApi() {
    override val usageModes = UsageMode.WITH_SERVERS

    override val paths = listOf(Path("/api/panel/servers/:id/metrics", RouteType.GET))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .pathParameter(param("id", numberSchema()))
            .queryParameter(optionalParam("range", stringSchema()))
            // Checked in the handler rather than read leniently like `range`: an override that is not
            // one of the three would otherwise be a silent fallback to the range's own buckets.
            .queryParameter(optionalParam("bucket", stringSchema()))
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        val parameters = getParameters(context)
        val id = parameters.pathParameter("id").long

        authProvider.requirePermission(ManageServersPermission(), context, id)

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

        val server = databaseManager.serverDao.getById(id, sqlClient) ?: throw NotExists()

        if (!server.permissionGranted) {
            throw NotExists()
        }

        val now = System.currentTimeMillis()

        val series = databaseManager.serverMetricDao
            .getSeries(id, now - range.durationMs, now, window.bucketMs, sqlClient, window.offsetMs(now))
            .map { metric ->
                JsonObject()
                    .put("ts", window.key(metric.ts))
                    .put("tps", metric.tps)
                    .put("mspt", metric.mspt)
                    .put("memUsed", metric.memUsed)
                    .put("memMax", metric.memMax)
                    .put("cpu", metric.cpu)
                    .put("players", metric.players)
                    .put("source", metric.source)
                    .put("diskUsed", metric.diskUsed)
                    .put("netRx", metric.netRx)
                    .put("netTx", metric.netTx)
                    .put("memRss", metric.memRss)
            }

        return Successful(
            mapOf(
                "range" to range.id,
                "bucketMs" to window.bucketMs,
                // Plus the memory of the host it runs on, which is what a node-sourced RAM
                // figure has to be read against (see ServerLatestMetrics).
                "latest" to ServerLatestMetrics.latestJson(
                    serverManager.getLatestMetrics(id),
                    ServerLatestMetrics.hostMemTotal(server.nodeId?.let { nodeManager.getLatestMetrics(it) })
                ),
                "series" to series,
                "capable" to server.hasCapability(ServerCapability.METRICS),
                // The panel needs to know which figures are measured from inside the game and
                // which are the node's view of the process, because they are not the same number.
                "features" to serverFeatureResolver.resolve(server).metrics.let {
                    JsonObject()
                        .put("tps", it.tps?.id)
                        .put("memory", it.memory?.id)
                        .put("players", it.players?.id)
                        .put("host", it.host)
                }
            )
        )
    }
}
