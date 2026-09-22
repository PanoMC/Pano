package com.panomc.platform.route.api.panel.server.metrics

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.panel.permission.ManageServersPermission
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.error.NotExists
import com.panomc.platform.model.*
import com.panomc.platform.server.metrics.ServerActivityChart
import com.panomc.platform.server.metrics.ServerActivityPeriod
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Parameters.optionalParam
import io.vertx.ext.web.validation.builder.Parameters.param
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.numberSchema
import io.vertx.json.schema.common.dsl.Schemas.stringSchema
import java.util.TimeZone

/**
 * The Server Activity chart of a server's overview: peak and average players per day (§2.4.19).
 *
 * The Website Activity card of the Statistics page, for one server — same buckets, same Week/Month
 * filter, same shape of map — drawn from `server_metric`, which already holds a per-minute player
 * count for every server that reports and keeps it for thirty days.
 *
 * Both maps are keyed by local midnight in milliseconds and carry every day of the period, zero
 * included, exactly like the statistics maps they sit next to.
 */
@Endpoint
class PanelGetServerActivityChartAPI(
    private val databaseManager: DatabaseManager,
    private val authProvider: AuthProvider
) : PanelApi() {
    override val paths = listOf(Path("/api/panel/servers/:id/activity-chart", RouteType.GET))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .pathParameter(param("id", numberSchema()))
            .queryParameter(optionalParam("period", stringSchema()))
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        val parameters = getParameters(context)
        val id = parameters.pathParameter("id").long

        authProvider.requirePermission(ManageServersPermission(), context, id)

        // Anything but a period this platform knows is the week view rather than a 400: the filter
        // is a link in the panel's address bar, and a stale bookmark should draw a chart.
        val period = ServerActivityPeriod.fromName(parameters.queryParameter("period")?.string)

        val sqlClient = getSqlClient()

        val server = databaseManager.serverDao.getById(id, sqlClient) ?: throw NotExists()

        if (!server.permissionGranted) {
            throw NotExists()
        }

        // Hour and Day read the minutes themselves, which `server_metric` keeps for thirty days:
        // sixty one-minute buckets and twenty-four one-hour ones, keyed by bucket start (§2.4.26).
        if (period == ServerActivityPeriod.HOUR || period == ServerActivityPeriod.DAY) {
            val (bucketMs, count) = if (period == ServerActivityPeriod.HOUR) {
                ServerActivityChart.MINUTE_MILLIS to MINUTES_IN_HOUR
            } else {
                ServerActivityChart.HOUR_MILLIS to HOURS_IN_DAY
            }

            val buckets = ServerActivityChart.recentBuckets(System.currentTimeMillis(), bucketMs, count)
            val measured = databaseManager.serverMetricDao.getPlayerActivity(id, buckets.first(), bucketMs, sqlClient)

            return Successful(
                mapOf(
                    "period" to period.name,
                    "peakPlayerData" to ServerActivityChart.peaksAt(buckets, measured),
                    "averagePlayerData" to ServerActivityChart.averagesAt(buckets, measured)
                )
            )
        }

        // The Year view reads the per-day rollup, which is kept for 400 days where the minutes behind
        // the Week and Month views are kept for 30 (§2.4.24). Same response shape either way.
        if (period == ServerActivityPeriod.YEAR) {
            val months = ServerActivityChart.monthBuckets(System.currentTimeMillis())
            val days = databaseManager.serverMetricDailyDao.getSince(id, months.first(), sqlClient)

            return Successful(
                mapOf(
                    "period" to period.name,
                    "peakPlayerData" to ServerActivityChart.monthlyPeaks(months, days),
                    "averagePlayerData" to ServerActivityChart.monthlyAverages(months, days)
                )
            )
        }

        val buckets = ServerActivityChart.dayBuckets(System.currentTimeMillis(), ServerActivityChart.amountOfDays(period))

        val measured = databaseManager.serverMetricDao.getDailyPlayerActivity(
            id,
            // The oldest bucket rather than the period's own start, so the day the chart draws
            // first is a whole day of figures and not the hours since this time last week.
            buckets.first(),
            TimeZone.getDefault().getOffset(System.currentTimeMillis()).toLong(),
            sqlClient
        )

        return Successful(
            mapOf(
                "period" to period.name,
                "peakPlayerData" to ServerActivityChart.peaks(buckets, measured),
                "averagePlayerData" to ServerActivityChart.averages(buckets, measured)
            )
        )
    }

    companion object {
        private const val MINUTES_IN_HOUR = 60
        private const val HOURS_IN_DAY = 24
    }
}
